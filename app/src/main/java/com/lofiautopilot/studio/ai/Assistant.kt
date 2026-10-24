package com.lofiautopilot.studio.ai

import com.lofiautopilot.studio.core.Graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ChatMsg(val fromUser: Boolean, val text: String, val steps: List<String> = emptyList(), val error: Boolean = false)

/** Chat that manages and fixes the app by prompt. Uses the free cloud AI when a key is set, otherwise the on-device model for simple commands. */
class Assistant(private val g: Graph) {
    private val tools = AppTools(g)
    private val history = mutableListOf<AiMsg>()
    private val _messages = MutableStateFlow(
        listOf(ChatMsg(false, "Hi! Ask me to make a video, change the schedule, fix a failed render, or arrange effects on your open project. For example: “put a flash on every drop” or “make it dreamy with purple glow on the bass”.")),
    )
    val messages: StateFlow<List<ChatMsg>> = _messages
    val busy = MutableStateFlow(false)

    private val system = """
You are the built-in assistant of "SE" (Synthetic Editor), an Android app that makes lofi music videos on this phone: it picks music and a picture from the user's folders, adds audio-reactive visual effects, renders the video, and can publish it to YouTube and Facebook on a schedule (Autopilot).
The user is not a developer. Reply in short, plain language. Use the tools to look at the real state before answering; never guess.
When something failed: read the job and its logs, explain the cause simply, fix it with a tool if you can (retry, change a setting), otherwise say exactly what the user must do.
Rules: never turn on live publishing (publishEnabled) or Autopilot unless the user clearly asks for it. You can't see or change API keys. Effect ids must come from list_effects. After changing something, say what you changed.
Today is ${java.text.SimpleDateFormat("EEEE d MMMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}.
""".trimIndent()

    private fun add(m: ChatMsg) { _messages.value = _messages.value + m }

    suspend fun send(text: String) {
        if (text.isBlank() || busy.value) return
        add(ChatMsg(true, text))
        busy.value = true
        try {
            if (g.engine.cloud.configured) viaCloud(text) else viaLocal(text)
        } catch (e: AiException) {
            add(ChatMsg(false, e.message.orEmpty() + if (e.hint.isNotBlank()) " ${e.hint}" else "", error = true))
        } catch (e: Exception) {
            add(ChatMsg(false, "Something went wrong: ${e.message}", error = true))
        } finally {
            busy.value = false
        }
    }

    private suspend fun viaCloud(text: String) {
        history.add(AiMsg.User(text))
        val steps = ArrayList<String>()
        val answer = g.engine.cloud.converse(system, history, tools.tools(), onStep = { steps.add(it) })
        add(ChatMsg(false, answer.ifBlank { "Done." }, steps))
    }

    private suspend fun viaLocal(text: String) {
        if (g.engine.llm.crashedLastTime) {
            add(ChatMsg(false, "The on-device AI stopped the app last time, so I turned it off to keep things safe. Re-enable it in Settings → AI if you want to try again, or add a free AI key.", error = true))
            return
        }
        if (!g.engine.llm.available) {
            add(ChatMsg(false, "I need either a free AI key or the on-device AI model. Add one in Settings → AI.", error = true))
            return
        }
        val names = listOf("get_status", "create_video", "retry_failed", "scan_folders", "set_setting", "job_action", "get_job_logs", "send_test_notification")
        val menu = tools.tools().filter { it.name in names }.joinToString("\n") { "- ${it.name}: ${it.description}" }
        val raw = g.engine.llm.ask(
            "You control an app. Pick ONE tool for the request, or answer in words. Reply with JSON only: {\"tool\":\"name\",\"args\":{...}} or {\"reply\":\"text\"}.\nTools:\n$menu\nset_setting args: {\"key\":\"autopilot\",\"value\":true}. job_action args: {\"job_id\":1,\"action\":\"retry\"}.",
            text, 300,
        )
        val a = raw?.indexOf('{') ?: -1; val b = raw?.lastIndexOf('}') ?: -1
        if (raw == null || a < 0 || b <= a) { add(ChatMsg(false, "I didn't understand that. Try a shorter request, or add a free AI key for open-ended help.")); return }
        val j = runCatching { JSONObject(raw.substring(a, b + 1)) }.getOrNull()
        if (j == null) { add(ChatMsg(false, "I didn't understand that.")); return }
        j.optString("reply").takeIf { it.isNotBlank() }?.let { add(ChatMsg(false, it)); return }
        val name = j.optString("tool")
        val tool = tools.tools().firstOrNull { it.name == name && name in names }
        if (tool == null) { add(ChatMsg(false, "I can't do that one offline.")); return }
        val out = tool.run(j.optJSONObject("args") ?: JSONObject())
        add(ChatMsg(false, out, listOf(name)))
    }

    fun clear() {
        history.clear()
        _messages.value = _messages.value.take(1)
    }
}
