package com.lofiautopilot.studio.ai

import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** [retryable] means "try the next provider": rate limit, server trouble or no network. */
class AiException(msg: String, val hint: String = "", val retryable: Boolean = false) : Exception(msg)

class ToolDef(val name: String, val description: String, val schema: JSONObject, val run: suspend (JSONObject) -> String)

data class ToolCall(val id: String, val name: String, val args: JSONObject)
data class ToolResult(val callId: String, val name: String, val output: String)

/** Provider-neutral chat history; each provider converts it to its own wire format. */
sealed interface AiMsg {
    data class User(val text: String) : AiMsg
    /** [geminiParts] keeps Gemini's raw reply so thought signatures survive the round trip. */
    data class Assistant(val text: String, val calls: List<ToolCall> = emptyList(), val geminiParts: JSONArray? = null) : AiMsg
    data class ToolResults(val results: List<ToolResult>) : AiMsg
}

enum class Provider(val id: String, val label: String, val secret: String, val defaultModel: String, val keyUrl: String) {
    GEMINI("gemini", "Gemini (recommended)", SecretStore.GEMINI, "gemini-2.5-flash", "aistudio.google.com/apikey"),
    GROQ("groq", "Groq", SecretStore.GROQ, "llama-3.3-70b-versatile", "console.groq.com/keys"),
    OPENROUTER("openrouter", "OpenRouter", SecretStore.OPENROUTER, "meta-llama/llama-3.3-70b-instruct:free", "openrouter.ai/keys");

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: GEMINI }
}

/**
 * Free cloud AI. Gemini (Google AI Studio free key) is the default; Groq and OpenRouter work as alternatives or as backups:
 * when the main one hits its free-tier limit, the next provider that has a key takes over automatically.
 * Only short text is sent (your chat, settings, music statistics). Never audio or pictures.
 */
class CloudAi(private val g: Graph) {
    private fun key(p: Provider) = g.secrets.get(p.secret)
    private fun model(p: Provider): String {
        val s = g.settings.value
        val m = when (p) { Provider.GEMINI -> s.geminiModel; Provider.GROQ -> s.groqModel; Provider.OPENROUTER -> s.openrouterModel }
        return m.ifBlank { p.defaultModel }
    }

    /** Providers that have a key, the chosen one first. */
    fun providers(): List<Provider> {
        val first = Provider.of(g.settings.value.aiProvider)
        return (listOf(first) + Provider.entries.filter { it != first }).filter { key(it).isNotBlank() }
    }

    val configured: Boolean get() = providers().isNotEmpty()

    suspend fun text(system: String, user: String, maxTokens: Int = 1500): String {
        val history = mutableListOf<AiMsg>(AiMsg.User(user))
        return withFallback { p -> step(p, system, history, emptyList(), maxTokens).text }
    }

    /** Short call used by Settings → "Test": returns "provider · model · reply". */
    suspend fun test(p: Provider): String {
        if (key(p).isBlank()) throw AiException("No key for ${p.label} yet.")
        val r = step(p, "Reply with the single word OK.", mutableListOf(AiMsg.User("ping")), emptyList(), 64)
        return "${p.label} works (${model(p)}): ${r.text.trim().take(40)}"
    }

    private suspend fun <T> withFallback(block: suspend (Provider) -> T): T {
        val list = providers()
        if (list.isEmpty()) throw AiException("No AI key yet.", "Add a free key in Settings → AI.")
        var last: AiException? = null
        for ((i, p) in list.withIndex()) {
            try { return block(p) }
            catch (e: AiException) {
                last = e
                if (!e.retryable) throw e
                if (i == list.lastIndex) {
                    // single provider (or all busy): wait a moment once, the per-minute limit often clears
                    delay(6000)
                    try { return block(p) } catch (e2: AiException) { last = e2 }
                }
                g.log("${p.label} is busy (${e.message}); trying the next one.", level = "warn")
            }
        }
        throw last ?: AiException("The AI service didn't answer.")
    }

    class Step(val text: String, val calls: List<ToolCall>, val geminiParts: JSONArray?)

    /** Runs the conversation until the model stops asking for tools. [history] is extended so the chat can continue. */
    suspend fun converse(system: String, history: MutableList<AiMsg>, tools: List<ToolDef>, onStep: (String) -> Unit = {}, maxLoops: Int = 8): String {
        var finalText = ""
        repeat(maxLoops) {
            val r = withFallback { p -> step(p, system, history, tools, 4000) }
            history.add(AiMsg.Assistant(r.text, r.calls, r.geminiParts))
            finalText = r.text
            if (r.calls.isEmpty()) return finalText
            val results = r.calls.map { call ->
                onStep("${call.name} ${call.args.toString().take(120)}")
                val tool = tools.firstOrNull { it.name == call.name }
                val out = if (tool == null) "Unknown tool ${call.name}" else try { tool.run(call.args) } catch (e: Exception) { "Error: ${e.message}" }
                ToolResult(call.id, call.name, out.take(6000))
            }
            history.add(AiMsg.ToolResults(results))
        }
        return finalText.ifBlank { "I ran out of steps before finishing. Ask me to continue." }
    }

    // ---------- one model call ----------

    private suspend fun step(p: Provider, system: String, history: List<AiMsg>, tools: List<ToolDef>, maxTokens: Int): Step =
        withContext(Dispatchers.IO) { if (p == Provider.GEMINI) gemini(system, history, tools, maxTokens) else openAiCompat(p, system, history, tools, maxTokens) }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject, who: String): JSONObject {
        val b = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        try {
            g.http.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrDefault("").ifBlank { text.take(160) }
                    throw when {
                        r.code == 429 -> AiException("$who is at its free limit for now.", "Wait a minute, or add a second provider's key as a backup.", retryable = true)
                        r.code >= 500 -> AiException("$who had a server problem (${r.code}).", "", retryable = true)
                        r.code == 401 || (r.code == 400 && msg.contains("API key", true)) -> AiException("$who didn't accept the API key.", "Check the key in Settings → AI.")
                        r.code == 403 -> AiException("$who refused the request.", "Check the key's permissions and that your country is supported. $msg".trim())
                        r.code == 404 -> AiException("$who doesn't know the model “${runCatching { body.optString("model") }.getOrDefault("")}”.", "Change the model name in Settings → AI.")
                        else -> AiException("$who error ${r.code}: $msg")
                    }
                }
                return JSONObject(text)
            }
        } catch (e: java.io.IOException) {
            throw AiException("Couldn't reach $who.", "Check the internet connection.", retryable = true)
        }
    }

    // ----- Gemini (Google AI Studio) -----

    /** Gemini rejects empty object schemas and untyped properties, so tidy ours. Objects without fields become JSON text. */
    private fun cleanSchema(s: JSONObject): JSONObject {
        val out = JSONObject()
        val type = s.optString("type", "string")
        if (type == "object") {
            val props = s.optJSONObject("properties")
            if (props == null || props.length() == 0) {
                return JSONObject().put("type", "string").put("description", (s.optString("description") + " (write it as a JSON object)").trim())
            }
            out.put("type", "object")
            val np = JSONObject()
            props.keys().forEach { k -> np.put(k, cleanSchema(props.getJSONObject(k))) }
            out.put("properties", np)
            s.optJSONArray("required")?.takeIf { it.length() > 0 }?.let { out.put("required", it) }
        } else if (type == "array") {
            out.put("type", "array")
            out.put("items", cleanSchema(s.optJSONObject("items") ?: JSONObject().put("type", "string")))
        } else {
            out.put("type", if (s.has("type")) type else "string")
            s.optJSONArray("enum")?.let { out.put("enum", it) }
        }
        s.optString("description").takeIf { it.isNotBlank() && !out.has("description") }?.let { out.put("description", it) }
        return out
    }

    private fun gemini(system: String, history: List<AiMsg>, tools: List<ToolDef>, maxTokens: Int): Step {
        val contents = JSONArray()
        for (m in history) when (m) {
            is AiMsg.User -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", m.text))))
            is AiMsg.Assistant -> {
                val parts = m.geminiParts ?: JSONArray().also { a ->
                    if (m.text.isNotBlank()) a.put(JSONObject().put("text", m.text))
                    m.calls.forEach { c -> a.put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args))) }
                    if (a.length() == 0) a.put(JSONObject().put("text", "…"))
                }
                contents.put(JSONObject().put("role", "model").put("parts", parts))
            }
            is AiMsg.ToolResults -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().also { a ->
                m.results.forEach { r -> a.put(JSONObject().put("functionResponse", JSONObject().put("name", r.name).put("response", JSONObject().put("result", r.output)))) }
            }))
        }
        val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens).put("temperature", 0.6).also { gc ->
                // 2.5 Flash can skip "thinking": faster, and the token budget goes to the answer
                if (model(Provider.GEMINI).startsWith("gemini-2.5-flash")) gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            })
        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            tools.forEach { t ->
                val d = JSONObject().put("name", t.name).put("description", t.description)
                if ((t.schema.optJSONObject("properties")?.length() ?: 0) > 0) d.put("parameters", cleanSchema(t.schema))
                decls.put(d)
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        val p = Provider.GEMINI
        val r = post("https://generativelanguage.googleapis.com/v1beta/models/${model(p)}:generateContent", mapOf("x-goog-api-key" to key(p)), body, p.label)
        val cand = r.optJSONArray("candidates")?.optJSONObject(0)
        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
        if (parts == null) {
            val why = r.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().ifBlank { cand?.optString("finishReason").orEmpty() }
            throw AiException("Gemini returned no answer${if (why.isNotBlank()) " ($why)" else ""}.", "Try rephrasing.")
        }
        val sb = StringBuilder(); val calls = ArrayList<ToolCall>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text") && !part.optBoolean("thought", false)) sb.append(part.getString("text"))
            part.optJSONObject("functionCall")?.let { fc -> calls.add(ToolCall("g${calls.size}", fc.getString("name"), fc.optJSONObject("args") ?: JSONObject())) }
        }
        return Step(sb.toString(), calls, parts)
    }

    // ----- Groq / OpenRouter (OpenAI-compatible) -----

    private fun openAiCompat(p: Provider, system: String, history: List<AiMsg>, tools: List<ToolDef>, maxTokens: Int): Step {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in history) when (m) {
            is AiMsg.User -> msgs.put(JSONObject().put("role", "user").put("content", m.text))
            is AiMsg.Assistant -> {
                val o = JSONObject().put("role", "assistant").put("content", if (m.text.isBlank() && m.calls.isNotEmpty()) JSONObject.NULL else m.text)
                if (m.calls.isNotEmpty()) o.put("tool_calls", JSONArray().also { a ->
                    m.calls.forEach { c -> a.put(JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString()))) }
                })
                msgs.put(o)
            }
            is AiMsg.ToolResults -> m.results.forEach { r -> msgs.put(JSONObject().put("role", "tool").put("tool_call_id", r.callId).put("content", r.output)) }
        }
        val body = JSONObject().put("model", model(p)).put("messages", msgs).put("max_tokens", maxTokens).put("temperature", 0.6)
        if (tools.isNotEmpty()) {
            body.put("tools", JSONArray().also { a -> tools.forEach { t -> a.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.schema))) } })
            body.put("tool_choice", "auto")
        }
        val url = if (p == Provider.GROQ) "https://api.groq.com/openai/v1/chat/completions" else "https://openrouter.ai/api/v1/chat/completions"
        val headers = mutableMapOf("Authorization" to "Bearer ${key(p)}")
        if (p == Provider.OPENROUTER) { headers["HTTP-Referer"] = "https://github.com/aim3-app"; headers["X-Title"] = "SE" }
        val r = post(url, headers, body, p.label)
        val msg = r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw AiException("${p.label} returned no answer.")
        val calls = ArrayList<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { a ->
            for (i in 0 until a.length()) {
                val c = a.getJSONObject(i); val f = c.getJSONObject("function")
                val args = runCatching { JSONObject(f.optString("arguments", "{}").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                calls.add(ToolCall(c.optString("id", "c$i"), f.getString("name"), args))
            }
        }
        return Step(msg.optString("content", "").let { if (it == "null") "" else it }, calls, null)
    }
}
