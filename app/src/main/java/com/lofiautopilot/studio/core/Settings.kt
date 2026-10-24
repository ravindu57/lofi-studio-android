package com.lofiautopilot.studio.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val DEFAULT_PROMPT = "Cozy anime-style illustration, a lone figure at a desk by a window at night, " +
    "soft rain on the glass, warm desk lamp glow, muted dusk palette, 16:9, no text."

@Serializable
data class AppSettings(
    val channelName: String = "My lofi channel",
    val onboarded: Boolean = false,

    // autopilot
    val autopilot: Boolean = false,
    val videosPerDay: Int = 1,
    val publishTime: String = "18:00",
    val leadHours: Float = 2f,

    // video
    val targetMinutes: Int = 60,
    val crossfadeS: Float = 4f,
    val fps: Int = 24,
    val quality: Int = 1080,
    val aspect: String = "WIDE",
    val codec: String = "H264",
    val lookName: String = "Cozy Lofi",
    val directorOn: Boolean = true,
    val saveToGallery: Boolean = true,
    /** Pictures cut together in each automatic video (1 = one picture the whole way). */
    val slideshowPics: Int = 1,
    /** Intro clip id placed at the start of automatic videos ("" = none, "auto" = pick one). */
    val introId: String = "",
    val keepDays: Int = 14,

    // pictures
    val imageFolderUri: String = "",
    val imageMode: String = "DIRECT",           // DIRECT = use the new picture as the artwork, REFERENCE = generate from it
    val defaultPrompt: String = DEFAULT_PROMPT,
    val refsPerPrompt: Int = 4,
    val thumbnailText: Boolean = false,
    val musicFolderUris: List<String> = emptyList(),

    // AI
    val planner: String = "rules",              // rules | local | cloud
    val aiProvider: String = "gemini",          // gemini | groq | openrouter
    val geminiModel: String = "gemini-2.5-flash",
    val groqModel: String = "llama-3.3-70b-versatile",
    val openrouterModel: String = "meta-llama/llama-3.3-70b-instruct:free",
    val localModelPath: String = "",
    val localModelId: String = "",

    // publishing (off = dry run)
    val publishEnabled: Boolean = false,
    val youtubeOn: Boolean = true,
    val facebookOn: Boolean = true,
    val ytAudited: Boolean = false,
    val ytCategory: String = "10",
    val ytChannelTitle: String = "",
    val fbPageId: String = "",
    val fbPageName: String = "",

    // notifications
    val notifyProgress: Boolean = true,
    val notifyDone: Boolean = true,
    val notifyFailed: Boolean = true,
    val notifyNewImage: Boolean = true,
    val webhookUrl: String = "",
    val telegramChatId: String = "",
    val ntfyTopic: String = "",

    // discover (niche feed)
    val nicheKeywords: List<String> = listOf("lofi hip hop", "study music", "chill beats"),
    val trendSort: String = "TRENDING",
    val trendRegion: String = "",
    val ideaHint: String = "",
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = state
    val value: AppSettings get() = state.value

    private fun load(): AppSettings = runCatching {
        prefs.getString("json", null)?.let { json.decodeFromString<AppSettings>(it) }
    }.getOrNull() ?: AppSettings()

    @Synchronized
    fun update(block: (AppSettings) -> AppSettings) {
        val n = block(state.value)
        state.value = n
        prefs.edit().putString("json", json.encodeToString(n)).apply()
    }
}
