package com.lofiautopilot.studio.auto

import android.content.Context
import android.net.Uri
import com.lofiautopilot.studio.audio.AudioAnalysis
import com.lofiautopilot.studio.audio.PcmDecoder
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.ImageEntity
import com.lofiautopilot.studio.data.KvEntity
import com.lofiautopilot.studio.data.TrackEntity

/** Keeps the Music and Pictures tables in step with the folders the user chose. */
class Library(private val ctx: Context, private val g: Graph) {
    private val audioExt = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic")

    private val moodWords = mapOf(
        "rain" to listOf("rain", "storm", "drizzle", "wet"),
        "night" to listOf("night", "midnight", "moon", "3am", "late"),
        "study" to listOf("study", "focus", "library", "desk", "code", "work"),
        "chill" to listOf("chill", "calm", "soft", "slow", "relax", "lazy", "slowed", "reverb"),
        "sleep" to listOf("sleep", "dream", "drift"),
        "autumn" to listOf("autumn", "fall", "leaves", "october"),
        "winter" to listOf("winter", "snow", "cold"),
        "summer" to listOf("summer", "sun", "beach"),
        "coffee" to listOf("coffee", "cafe", "café", "tea"),
        "drive" to listOf("drive", "highway", "car", "road", "tram", "train"),
        "jazz" to listOf("jazz", "piano", "sax"),
        "energy" to listOf("edm", "nonstop", "remix", "dj", "bass", "party"),
    )

    fun guessMoods(text: String): List<String> {
        val t = text.lowercase()
        return moodWords.filter { (_, ws) -> ws.any { it in t } }.keys.toList()
    }

    private fun nice(name: String) = name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()

    /** Returns the number of new tracks found. */
    suspend fun scanMusic(): Int {
        val dao = g.db.tracks()
        val seen = ArrayList<String>()
        var added = 0
        for (u in g.settings.value.musicFolderUris) {
            val tree = Uri.parse(u)
            for (f in Saf.walk(ctx, tree)) {
                val ext = f.name.substringAfterLast('.', "").lowercase()
                if (ext !in audioExt && !f.mime.startsWith("audio/")) continue
                val key = f.uri.toString()
                seen.add(key)
                if (dao.byUri(key) != null) continue
                val dur = PcmDecoder.probe(ctx, f.uri)
                val title = nice(f.name)
                dao.insert(TrackEntity(uri = key, title = title, durationMs = dur / 1000, moods = guessMoods(title).joinToString(",")))
                added++
            }
        }
        // anything not seen any more is hidden but kept (in case the folder was just unmounted)
        if (seen.isNotEmpty()) dao.markMissing(seen)
        return added
    }

    /** Measures loudness for tracks that don't have it yet, so mixes sound even without extra passes. */
    suspend fun measureLoudness(onEach: (Int, Int) -> Unit = { _, _ -> }) {
        val dao = g.db.tracks()
        val todo = dao.allNow().filter { it.present && it.lufs == null && it.durationMs > 0 }
        todo.forEachIndexed { i, t ->
            onEach(i, todo.size)
            val l = AudioAnalysis.quickLufs(ctx, Uri.parse(t.uri))
            if (l != null) dao.update(t.copy(lufs = l))
        }
    }

    /** Scans the watched picture folder. Returns the pictures that appeared since the last scan. */
    suspend fun scanImages(): List<ImageEntity> {
        val folder = g.settings.value.imageFolderUri
        if (folder.isBlank()) return emptyList()
        val dao = g.db.images()
        val first = g.db.kv().get("images_scanned") == null
        val seen = ArrayList<String>()
        val fresh = ArrayList<ImageEntity>()
        for (f in Saf.walk(ctx, Uri.parse(folder))) {
            val ext = f.name.substringAfterLast('.', "").lowercase()
            if (ext !in imageExt && !f.mime.startsWith("image/")) continue
            val key = f.uri.toString()
            seen.add(key)
            if (dao.byUri(key) != null) continue
            val e = ImageEntity(uri = key, name = f.name, modified = f.modified, size = f.size)
            dao.insert(e)
            fresh.add(e)
        }
        if (seen.isNotEmpty()) dao.markMissing(seen)
        g.db.kv().put(KvEntity("images_scanned", System.currentTimeMillis().toString()))
        return if (first) emptyList() else fresh
    }
}
