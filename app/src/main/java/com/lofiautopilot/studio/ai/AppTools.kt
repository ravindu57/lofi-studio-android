package com.lofiautopilot.studio.ai

import com.lofiautopilot.studio.core.AppSettings
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.LookEntity
import com.lofiautopilot.studio.vfx.EffectLibrary
import com.lofiautopilot.studio.vfx.Layer
import com.lofiautopilot.studio.vfx.LayerFactory
import com.lofiautopilot.studio.vfx.Look
import com.lofiautopilot.studio.vfx.Presets
import com.lofiautopilot.studio.vfx.React
import com.lofiautopilot.studio.vfx.Blend
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The things the AI assistant (cloud or on-device) is allowed to do in the app. */
class AppTools(private val g: Graph) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    private fun schema(props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))

    private fun p(type: String, desc: String, vararg enum: String): JSONObject =
        JSONObject().put("type", type).put("description", desc).also { if (enum.isNotEmpty()) it.put("enum", JSONArray(enum.toList())) }

    private fun jobLine(j: com.lofiautopilot.studio.data.JobEntity) =
        "#${j.id} [${j.status}/${j.stage}] ${j.title.ifBlank { "(untitled)" }} ${(j.progress * 100).toInt()}% ${j.phase}" +
            (if (j.error.isNotBlank()) " ERROR: ${j.error} ${j.hint}" else "") + (if (j.ytUrl.isNotBlank()) " YT:${j.ytUrl}" else "") + (if (j.fbUrl.isNotBlank()) " FB:${j.fbUrl}" else "")

    /** Settings the assistant may change, with a plain description. Secrets and folder grants are deliberately not in this list. */
    private val editable = setOf(
        "channelName", "autopilot", "videosPerDay", "publishTime", "leadHours", "targetMinutes", "crossfadeS", "fps", "quality", "aspect", "codec",
        "lookName", "directorOn", "saveToGallery", "keepDays", "imageMode", "defaultPrompt", "refsPerPrompt", "thumbnailText", "planner",
        "aiProvider", "geminiModel", "groqModel", "openrouterModel", "publishEnabled", "youtubeOn", "facebookOn", "ytAudited", "ytCategory", "notifyProgress", "notifyDone", "notifyFailed", "notifyNewImage",
    )

    fun tools(): List<ToolDef> = listOf(
        ToolDef("get_status", "Overview of the app: autopilot, queue, pictures, music, AI, and the settings.", schema()) {
            val s = g.settings.value
            val jobs = g.db.jobs().recentNow(5)
            val newImgs = g.db.images().newOnes().size
            val tracks = g.db.tracks().usable().size
            buildString {
                appendLine("Autopilot: ${if (s.autopilot) "ON" else "off"} (${s.videosPerDay}/day at ${s.publishTime}); publishing ${if (s.publishEnabled) "LIVE" else "off (practice mode)"}")
                appendLine("Music tracks: $tracks. New unused pictures: $newImgs. Picture mode: ${s.imageMode}. Look: ${s.lookName}. Planner: ${s.planner}.")
                appendLine("Recent videos:"); jobs.forEach { appendLine(jobLine(it)) }
            }
        },
        ToolDef("list_jobs", "List recent videos with status, progress and any error.", schema(JSONObject().put("limit", p("integer", "How many (default 10)")))) {
            g.db.jobs().recentNow(it.optInt("limit", 10).coerceIn(1, 40)).joinToString("\n") { j -> jobLine(j) }.ifBlank { "No videos yet." }
        },
        ToolDef("get_job_logs", "Read the activity log for a video (or the latest lines if no id) to diagnose a problem.",
            schema(JSONObject().put("job_id", p("integer", "Video id, optional")).put("lines", p("integer", "default 30")))) {
            val id = it.optLong("job_id", 0); val n = it.optInt("lines", 30).coerceIn(5, 100)
            val rows = if (id > 0) g.db.logs().forJob(id, n) else g.db.logs().recentNow(n)
            rows.reversed().joinToString("\n") { l -> "${fmt.format(Date(l.ts))} [${l.level}] ${l.text}" }.ifBlank { "No log lines." }
        },
        ToolDef("create_video", "Queue a new video now using the automation (music chosen by the app, newest picture).",
            schema(JSONObject().put("minutes", p("integer", "Length in minutes, optional")))) {
            val id = g.engine.createJob("manual", minutes = it.optInt("minutes", 0).takeIf { m -> m > 0 })
            com.lofiautopilot.studio.auto.RenderService.start(g.app)
            "Queued video #$id."
        },
        ToolDef("job_action", "Pause, resume, cancel, retry, publish_now or delete a video.",
            schema(JSONObject().put("job_id", p("integer", "Video id")).put("action", p("string", "What to do", "pause", "resume", "cancel", "retry", "publish_now", "delete")), listOf("job_id", "action"))) {
            val r = g.engine.setStatus(it.getLong("job_id"), it.getString("action"))
            if (it.getString("action") in listOf("retry", "resume", "publish_now")) com.lofiautopilot.studio.auto.RenderService.start(g.app)
            r
        },
        ToolDef("edit_metadata", "Change the title, description and tags of a video that isn't published yet.",
            schema(JSONObject().put("job_id", p("integer", "Video id")).put("title", p("string", "New title")).put("description", p("string", "New description"))
                .put("tags", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))), listOf("job_id", "title"))) {
            val j = g.db.jobs().get(it.getLong("job_id")) ?: return@ToolDef "No such video."
            val tags = it.optJSONArray("tags")?.let { a -> List(a.length()) { i -> a.getString(i) } } ?: j.tags.split(",")
            g.engine.updateMetadata(j.id, it.getString("title"), it.optString("description", j.description), tags)
        },
        ToolDef("get_settings", "Show the current settings that can be changed.", schema()) {
            val o = json.encodeToJsonElementSafe(g.settings.value)
            o.filterKeys { k -> k in editable }.entries.joinToString("\n") { (k, v) -> "$k = $v" }
        },
        ToolDef("set_setting", "Change one setting. Value is JSON (true, 3, \"18:30\").",
            schema(JSONObject().put("key", p("string", "Setting name")).put("value", JSONObject().put("type", "string").put("description", "New value: true/false, a number, or text")), listOf("key", "value"))) {
            val key = it.getString("key")
            if (key !in editable) return@ToolDef "“$key” can't be changed from here. Changeable: ${editable.joinToString()}"
            val cur = json.encodeToJsonElementSafe(g.settings.value).toMutableMap()
            val raw = it.get("value")
            val asText = raw.toString()
            cur[key] = when {
                raw is Boolean -> JsonPrimitive(raw); raw is Int -> JsonPrimitive(raw); raw is Long -> JsonPrimitive(raw); raw is Double -> JsonPrimitive(raw)
                asText.equals("true", true) -> JsonPrimitive(true); asText.equals("false", true) -> JsonPrimitive(false)
                asText.toLongOrNull() != null -> JsonPrimitive(asText.toLong()); asText.toDoubleOrNull() != null -> JsonPrimitive(asText.toDouble())
                else -> JsonPrimitive(asText)
            }
            val next = runCatching { json.decodeFromString<AppSettings>(JsonObject(cur).toString()) }.getOrNull() ?: return@ToolDef "That value doesn't fit “$key”."
            g.settings.update { next }
            if (key == "autopilot" && next.autopilot) com.lofiautopilot.studio.auto.RenderService.start(g.app)
            "$key is now ${cur[key]}."
        },
        ToolDef("list_effects", "List the visual effects available for the editor (id, name, category, parameters).",
            schema(JSONObject().put("category", p("string", "Optional category filter")))) {
            val cat = it.optString("category")
            EffectLibrary.all.filter { d -> cat.isBlank() || d.category.equals(cat, true) }.joinToString("\n") { d ->
                "${d.id} | ${d.name} | ${d.category} | ${d.kind} | params: ${d.params.joinToString { p -> "${p.key}(${p.min}..${p.max}, def ${p.def})" }}"
            }
        },
        ToolDef("list_looks", "List built-in and saved looks (effect stacks).", schema()) {
            val saved = g.db.looks().allNow().map { l -> l.name }
            "Built-in: " + Presets.builtin.joinToString { l -> "${l.name} (${l.note})" } + "\nSaved: " + saved.joinToString().ifBlank { "none" }
        },
        ToolDef("design_look", "Create a custom look from effect layers and (optionally) open it in the Studio editor and/or save it. Use list_effects first to get valid ids and parameter ranges. Layers apply in order.",
            schema(JSONObject()
                .put("name", p("string", "Name for the look"))
                .put("layers", JSONObject().put("type", "array").put("description", "Effect layers").put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("fx", p("string", "Effect id from list_effects"))
                    .put("intensity", p("number", "0..1.5"))
                    .put("opacity", p("number", "0..1"))
                    .put("react", p("string", "Audio source", "OFF", "BASS", "MID", "HIGH", "ENERGY", "BEAT"))
                    .put("react_amount", p("number", "0..2"))
                    .put("blend", p("string", "Blend for overlay effects", "NORMAL", "ADD", "SCREEN", "OVERLAY"))
                    .put("params", JSONObject().put("type", "object").put("description", "parameter key -> number"))
                    .put("color1", p("string", "Hex like #ff8800, optional")).put("color2", p("string", "Hex, optional"))).put("required", JSONArray(listOf("fx")))))
                .put("open_in_studio", p("boolean", "Load it into the editor"))
                .put("save", p("boolean", "Save it to My Looks"))
                .put("use_for_autopilot", p("boolean", "Use it for automatic videos")), listOf("name", "layers"))) { a ->
            val name = a.getString("name")
            val arr = a.getJSONArray("layers")
            val layers = ArrayList<Layer>(); val notes = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val def = EffectLibrary.get(o.getString("fx"))
                if (def == null) { notes.add("skipped unknown effect ${o.getString("fx")}"); continue }
                var l = LayerFactory.create(def)
                val params = HashMap(l.p)
                o.obj("params")?.let { po -> po.keys().forEach { k -> def.params.firstOrNull { p -> p.key == k }?.let { pd -> params[k] = po.getDouble(k).toFloat().coerceIn(pd.min, pd.max) } ?: notes.add("${def.id} has no parameter $k") } }
                l = l.copy(
                    p = params, intensity = o.optDouble("intensity", 1.0).toFloat().coerceIn(0f, 1.5f), opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                    react = runCatching { React.valueOf(o.optString("react", l.react.name).uppercase()) }.getOrDefault(l.react),
                    reactAmt = o.optDouble("react_amount", l.reactAmt.toDouble()).toFloat().coerceIn(0f, 2f),
                    blend = runCatching { Blend.valueOf(o.optString("blend", l.blend.name).uppercase()) }.getOrDefault(l.blend),
                    c1 = hex(o.optString("color1")), c2 = hex(o.optString("color2")),
                )
                layers.add(l)
            }
            if (layers.isEmpty()) return@ToolDef "No valid layers. ${notes.joinToString()}"
            val look = Look(name, layers)
            val opened = if (a.optBoolean("open_in_studio", true)) g.editor?.let { it.applyLook(look.layers, look.fit, true); true } ?: false else false
            if (a.optBoolean("save", false) || a.optBoolean("use_for_autopilot", false)) {
                val ex = g.db.looks().byName(name)
                val js = json.encodeToString(look)
                if (ex != null) g.db.looks().update(ex.copy(json = js)) else g.db.looks().insert(LookEntity(name = name, json = js))
            }
            if (a.optBoolean("use_for_autopilot", false)) g.settings.update { it.copy(lookName = name) }
            "Look “$name” built with ${layers.size} layers." + (if (opened) " Applied to the open project." else " (No editor is open; saved only if requested.)") + if (notes.isNotEmpty()) " Notes: ${notes.joinToString()}" else ""
        },
        ToolDef("get_project", "Describe the project open in the timeline editor: media, music clips and effect clips with their times.", schema()) {
            val m = g.editor ?: return@ToolDef "No project is open in the editor. Ask the user to open one in the Studio tab."
            val p = m.project
            buildString {
                appendLine("Project “${p.name}”, ${"%.1f".format(p.durationS)} s, ${p.aspect}, ${p.quality}p ${p.fps}fps")
                appendLine("Media: ${if (p.media.isEmpty()) "none" else p.media.joinToString { m -> if (m.isIntro) "intro" else if (m.video) "video" else "picture" }}")
                p.audio.forEach { appendLine("Music ${it.id}: ${it.title} at ${"%.1f".format(it.startS)}-${"%.1f".format(it.endS)} s vol ${it.gain}") }
                p.effects.forEach { appendLine("Effect ${it.id}: ${it.fx} ${"%.1f".format(it.from)}-${"%.1f".format(p.layerEnd(it))} s lane ${it.track} intensity ${it.intensity}${if (it.auto) " (AI)" else ""}") }
            }
        },
        ToolDef("add_effect_clip", "Place an effect clip on the open project's timeline between two times.",
            schema(JSONObject().put("fx", p("string", "Effect id from list_effects")).put("start", p("number", "Start second")).put("end", p("number", "End second"))
                .put("intensity", p("number", "0..1.5")).put("params", JSONObject().put("type", "object").put("description", "parameter key -> number")), listOf("fx", "start", "end"))) { a ->
            val m = g.editor ?: return@ToolDef "No project is open in the editor."
            val def = EffectLibrary.get(a.getString("fx")) ?: return@ToolDef "Unknown effect ${a.getString("fx")}."
            val from = a.getDouble("start").toFloat().coerceAtLeast(0f); val to = a.getDouble("end").toFloat().coerceAtLeast(from + 0.3f)
            val params = HashMap(LayerFactory.create(def).p)
            a.obj("params")?.let { po -> po.keys().forEach { k -> def.params.firstOrNull { x -> x.key == k }?.let { pd -> params[k] = po.getDouble(k).toFloat().coerceIn(pd.min, pd.max) } } }
            val lane = m.freeLane(m.project.effects, from, to)
            val layer = LayerFactory.create(def).copy(from = from, to = to, track = lane, p = params, intensity = a.optDouble("intensity", 1.0).toFloat().coerceIn(0f, 1.5f))
            m.edit { it.copy(effects = it.effects + layer) }
            "Added ${def.name} from ${"%.1f".format(from)} to ${"%.1f".format(to)} s."
        },
        ToolDef("add_music_clip", "Add a song from the library to the open project (matched by part of its title).",
            schema(JSONObject().put("title_contains", p("string", "Part of the track title")), listOf("title_contains"))) { a ->
            val m = g.editor ?: return@ToolDef "No project is open in the editor."
            val q = a.getString("title_contains").lowercase()
            val t = g.db.tracks().usable().firstOrNull { it.title.lowercase().contains(q) } ?: return@ToolDef "No track matches “$q”."
            m.addMusic(t.uri, t.title, t.durationMs / 1000f, t.lufs)
            "Added “${t.title}”."
        },
        ToolDef("auto_arrange", "Let the AI place effect clips and colours on the open project's timeline to match the music and an optional style request such as 'dreamy rain, purple glow'. Replaces earlier AI clips, keeps manual ones.",
            schema(JSONObject().put("style_request", p("string", "What the user wants it to look like, in their words (optional)")))) { a ->
            val m = g.editor ?: return@ToolDef "No project is open in the editor."
            val ft = m.features ?: return@ToolDef "The music is still being analysed. Try again in a moment."
            val r = g.engine.arranger.arrange(m.project, ft, true, a.optString("style_request"))
            m.edit { p -> p.copy(effects = p.effects.filter { !it.auto } + r.layers) }
            "Placed ${r.layers.size} clips in “${r.plan.style}” style (${r.by})." + if (r.notes.isNotEmpty()) " " + r.notes.joinToString(" ") else ""
        },
        ToolDef("clear_effects", "Remove all effect clips from the open project.", schema()) {
            val m = g.editor ?: return@ToolDef "No project is open in the editor."
            m.clearEffects(); "Cleared."
        },
        ToolDef("scan_folders", "Rescan the music and picture folders now.", schema()) {
            val m = g.engine.library.scanMusic(); val i = g.engine.library.scanImages()
            "Music: $m new track(s). Pictures: ${i.size} new."
        },
        ToolDef("list_pictures", "Show recent pictures found in the watched folder and whether they are new or used.", schema()) {
            g.db.images().newOnes().take(15).joinToString("\n") { i -> "new: ${i.name}" }.ifBlank { "No new pictures." }
        },
        ToolDef("retry_failed", "Retry every failed video.", schema()) {
            val failed = g.db.jobs().recentNow(30).filter { j -> j.status == "failed" }
            failed.forEach { j -> g.engine.setStatus(j.id, "retry") }
            if (failed.isNotEmpty()) com.lofiautopilot.studio.auto.RenderService.start(g.app)
            "Retrying ${failed.size} video(s)."
        },
        ToolDef("send_test_notification", "Send a test notification to the phone (and Telegram/Discord/ntfy if set up).", schema()) {
            g.notifier.test(); "Sent."
        },
    )

    /** Reads a JSON object that a model may send either as an object or as JSON text. */
    private fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key) ?: optString(key).takeIf { it.trimStart().startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun hex(s: String): Int? = runCatching { android.graphics.Color.parseColor(s) }.getOrNull()

    private fun Json.encodeToJsonElementSafe(s: AppSettings): JsonObject = parseToJsonElement(encodeToString(s)).jsonObject
}
