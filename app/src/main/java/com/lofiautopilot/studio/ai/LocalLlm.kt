package com.lofiautopilot.studio.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

data class ModelInfo(
    val id: String, val name: String, val blurb: String, val sizeMb: Int, val url: String,
    val template: String, val needsToken: Boolean = false,
)

object LocalModels {
    const val QWEN = "qwen"
    const val GEMMA = "gemma"
    val catalog = listOf(
        ModelInfo(
            "qwen2.5-0.5b", "Qwen 2.5 · 0.5B (small & fast)", "Good for choosing looks and writing titles. About 0.5 GB.", 547,
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            QWEN,
        ),
        ModelInfo(
            "qwen2.5-1.5b", "Qwen 2.5 · 1.5B (smarter)", "Better at following instructions and JSON. About 1.6 GB. Your phone has room for it.", 1600,
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            QWEN,
        ),
        ModelInfo(
            "gemma3-1b", "Gemma 3 · 1B (needs a free Hugging Face token)", "Google's small model. Accept its licence on huggingface.co, then paste a read token in Settings.", 555,
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            GEMMA, needsToken = true,
        ),
    )
    fun byId(id: String) = catalog.firstOrNull { it.id == id }
}

/** Downloads and runs a small language model fully on the phone. */
class LocalLlm(private val ctx: Context, private val g: Graph) {
    private val lock = Mutex()
    private var llm: LlmInference? = null
    private var loadedPath = ""

    private val _dl = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _dl

    data class Download(val modelId: String, val done: Long, val total: Long, val error: String = "", val finished: Boolean = false) {
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    fun modelDir() = File(ctx.filesDir, "models").apply { mkdirs() }
    fun fileFor(m: ModelInfo) = File(modelDir(), m.url.substringAfterLast('/'))
    /** A finished download only ever lives at the final path (partial ones are .part files), so size is just a sanity check. */
    fun installed(m: ModelInfo) = fileFor(m).let { it.exists() && it.length() > m.sizeMb * 1_000_000L * 0.5 }

    fun activeModel(): ModelInfo? {
        val s = g.settings.value
        val m = LocalModels.byId(s.localModelId) ?: return null
        return if (installed(m)) m else null
    }

    private val guard = ctx.getSharedPreferences("llm_guard", Context.MODE_PRIVATE)

    /** True if the previous on-device AI run never finished, which means the app was killed inside the model. */
    val crashedLastTime: Boolean get() = guard.getBoolean("disabled", false)

    init {
        if (guard.getBoolean("inflight", false)) {
            // Only blame the model if the process really crashed. Swiping the app away mid-answer is not a crash.
            var crashed = false
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                crashed = runCatching {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
                    last != null && (last.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || last.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                        (last.reason == android.app.ApplicationExitInfo.REASON_SIGNALED && last.status in listOf(6, 11)))
                }.getOrDefault(false)
            }
            guard.edit().putBoolean("disabled", crashed).putBoolean("inflight", false).apply()
        }
    }

    fun reEnable() { guard.edit().putBoolean("disabled", false).putBoolean("inflight", false).apply() }

    val available: Boolean get() = !crashedLastTime && activeModel() != null

    /** Resumable download with progress. Safe to call again after an interruption. */
    suspend fun download(m: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        val f = fileFor(m)
        val part = File(f.path + ".part")
        try {
            var have = if (part.exists()) part.length() else 0L
            var expect = -1L
            val b = Request.Builder().url(m.url)
            if (m.needsToken) {
                val t = g.secrets.get(SecretStore.HF_TOKEN)
                if (t.isBlank()) { _dl.value = Download(m.id, 0, 0, "This model needs a Hugging Face token. Paste one in Settings → AI."); return@withContext false }
                b.header("Authorization", "Bearer $t")
            }
            if (have > 0) b.header("Range", "bytes=$have-")
            g.http.newBuilder().readTimeout(java.time.Duration.ofSeconds(60)).build().newCall(b.build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) { _dl.value = Download(m.id, 0, 0, "Hugging Face refused the download. Check the token and that you accepted the model's licence."); return@withContext false }
                if (!r.isSuccessful && r.code != 206) { _dl.value = Download(m.id, 0, 0, "Download failed (${r.code})."); return@withContext false }
                if (r.code == 200 && have > 0) { part.delete(); have = 0 }
                val body = r.body ?: return@withContext false
                val total = body.contentLength() + have
                expect = if (body.contentLength() > 0) total else -1L
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(have)
                    val buf = ByteArray(256 * 1024)
                    var done = have
                    var last = 0L
                    body.byteStream().use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            done += n
                            if (done - last > 1_000_000) { last = done; _dl.value = Download(m.id, done, total) }
                        }
                    }
                }
            }
            if (expect > 0 && part.length() < expect) {
                _dl.value = Download(m.id, part.length(), expect, "Download stopped early (${part.length() / 1_000_000} of ${expect / 1_000_000} MB). Tap again to resume.")
                return@withContext false
            }
            f.delete()
            if (!part.renameTo(f)) { part.copyTo(f, overwrite = true); part.delete() }
            _dl.value = Download(m.id, f.length(), f.length(), finished = true)
            g.settings.update { it.copy(localModelId = m.id, localModelPath = f.absolutePath, planner = if (it.planner == "rules") "local" else it.planner) }
            true
        } catch (e: Exception) {
            _dl.value = Download(m.id, part.length(), 0, "Download interrupted: ${e.message ?: "network error"}. Tap again to resume.")
            false
        }
    }

    fun delete(m: ModelInfo) {
        fileFor(m).delete(); File(fileFor(m).path + ".part").delete()
        if (g.settings.value.localModelId == m.id) g.settings.update { it.copy(localModelId = "", localModelPath = "") }
        release()
    }

    fun format(system: String, user: String, template: String): String = when (template) {
        LocalModels.GEMMA -> "<start_of_turn>user\n$system\n\n$user<end_of_turn>\n<start_of_turn>model\n"
        else -> "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Runs the model. Returns null if no model is installed or generation fails (callers fall back to rules). */
    suspend fun ask(system: String, user: String, maxTokens: Int = 512): String? = withContext(Dispatchers.Default) {
        if (crashedLastTime) return@withContext null
        val m = activeModel() ?: return@withContext null
        // The models have a 1280-token window for prompt + answer. ~3.2 characters per token; keep the prompt small enough to leave room.
        if ((system.length + user.length) > 2400) { g.log("On-device AI prompt too long (${system.length + user.length} chars); skipped.", level = "warn"); return@withContext null }
        lock.withLock {
            try {
                guard.edit().putBoolean("inflight", true).commit()
                val path = fileFor(m).absolutePath
                if (llm == null || loadedPath != path) {
                    llm?.close()
                    llm = create(path)
                    loadedPath = path
                }
                val out = llm!!.generateResponse(format(system, user, m.template))
                guard.edit().putBoolean("inflight", false).commit()
                out
            } catch (e: Throwable) {
                guard.edit().putBoolean("inflight", false).commit()
                g.log("Local AI failed: ${e.message}", level = "warn")
                release()
                null
            }
        }
    }

    /**
     * CPU only: the GPU backend can crash the whole process natively on some phones, which nothing can catch.
     * The models here are built with a 1280-token window (prompt + answer), so that is the limit we ask for.
     */
    private fun create(path: String): LlmInference = LlmInference.createFromOptions(
        ctx,
        LlmInference.LlmInferenceOptions.builder().setModelPath(path).setMaxTokens(1280)
            .setPreferredBackend(LlmInference.Backend.CPU).build(),
    )

    fun release() {
        runCatching { llm?.close() }
        llm = null; loadedPath = ""
    }

    /** Chooses visuals for a piece of music. Falls back to rules whenever the model is missing or confused. */
    suspend fun direct(summary: MusicSummary): DirectorPlan {
        val rules = Director.rules(summary)
        if (!available) return rules
        val sys = "You are a visual director for lofi music videos. You read numbers describing a mix and reply with ONE JSON object only."
        val user = buildString {
            append(summary.toPrompt()).append('\n')
            append("Choose a visual style from: ${Director.STYLES.keys.joinToString(", ")}.\n")
            append("Reply with JSON: {\"style\":\"...\",\"intensity\":0.5-1.4,\"reactivity\":0.3-1.6,\"boosts\":[one number 0.6-1.4 per listed section],\"why\":\"short reason\"}\n")
            append("Quiet sections get lower boosts, loud/drop sections higher. Slow quiet music suits calm, rainy or dreamy; fast loud music suits energetic or retro.")
        }
        val text = ask(sys, user, 400) ?: return rules
        return Director.parse(text, summary) ?: rules
    }
}
