package com.lofiautopilot.studio.publish

import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

class PublishException(msg: String, val hint: String = "", val retry: Boolean = true) : Exception(msg)

data class UploadResult(val id: String, val url: String, val privacy: String, val thumbnailSet: Boolean)

interface VideoUploader {
    suspend fun upload(video: File, title: String, description: String, tags: List<String>, thumb: File?, publishAtMs: Long, onProgress: (Float) -> Unit): UploadResult
}

class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)

/** YouTube sign-in uses Google's "TV and limited input" flow, so no redirect or signing key is needed on the phone. */
class YouTubeAuth(private val g: Graph) {
    private val scope = "https://www.googleapis.com/auth/youtube.upload https://www.googleapis.com/auth/youtube.readonly"

    val connected get() = g.secrets.has(SecretStore.YT_REFRESH)

    suspend fun start(): DeviceCode = withContext(Dispatchers.IO) {
        val id = g.secrets.get(SecretStore.YT_CLIENT_ID)
        if (id.isBlank()) throw PublishException("Add the Google client ID first.", "Settings → Accounts → YouTube.", retry = false)
        val r = g.http.newCall(Request.Builder().url("https://oauth2.googleapis.com/device/code")
            .post(FormBody.Builder().add("client_id", id).add("scope", scope).build()).build()).execute()
        r.use {
            val j = JSONObject(it.body?.string().orEmpty())
            if (!it.isSuccessful) throw PublishException("Google said: ${j.optString("error_description", j.optString("error"))}", "Check the client ID and that it is a “TVs and Limited Input devices” client.", retry = false)
            DeviceCode(j.getString("device_code"), j.getString("user_code"), j.optString("verification_url", "https://www.google.com/device"), j.getInt("expires_in"), j.optInt("interval", 5))
        }
    }

    /** Waits for the user to approve on another screen. Returns true when connected. */
    suspend fun finish(code: DeviceCode): Boolean = withContext(Dispatchers.IO) {
        val end = System.currentTimeMillis() + code.expiresIn * 1000L
        var wait = code.interval
        while (System.currentTimeMillis() < end) {
            delay(wait * 1000L)
            val body = FormBody.Builder().add("client_id", g.secrets.get(SecretStore.YT_CLIENT_ID))
                .add("client_secret", g.secrets.get(SecretStore.YT_CLIENT_SECRET))
                .add("device_code", code.deviceCode).add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
            g.http.newCall(Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()).execute().use { r ->
                val j = JSONObject(r.body?.string().orEmpty())
                if (r.isSuccessful && j.has("refresh_token")) {
                    g.secrets.put(SecretStore.YT_REFRESH, j.getString("refresh_token"))
                    cache = j.getString("access_token") to (System.currentTimeMillis() + j.optLong("expires_in", 3000) * 1000 - 60_000)
                    return@withContext true
                }
                when (j.optString("error")) {
                    "authorization_pending" -> {}
                    "slow_down" -> wait += 5
                    "access_denied" -> return@withContext false
                    "" -> {}
                    else -> throw PublishException("Google said: ${j.optString("error_description", j.optString("error"))}", retry = false)
                }
            }
        }
        false
    }

    fun disconnect() { g.secrets.put(SecretStore.YT_REFRESH, ""); cache = null }

    private var cache: Pair<String, Long>? = null

    suspend fun token(): String = withContext(Dispatchers.IO) {
        cache?.let { if (it.second > System.currentTimeMillis()) return@withContext it.first }
        val rt = g.secrets.get(SecretStore.YT_REFRESH)
        if (rt.isBlank()) throw PublishException("YouTube isn't connected.", "Connect YouTube in Settings → Accounts.", retry = false)
        val body = FormBody.Builder().add("client_id", g.secrets.get(SecretStore.YT_CLIENT_ID)).add("client_secret", g.secrets.get(SecretStore.YT_CLIENT_SECRET))
            .add("refresh_token", rt).add("grant_type", "refresh_token").build()
        g.http.newCall(Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()).execute().use { r ->
            val j = JSONObject(r.body?.string().orEmpty())
            if (!r.isSuccessful) throw PublishException("YouTube connection expired.", "Reconnect YouTube in Settings → Accounts.", retry = false)
            val t = j.getString("access_token")
            cache = t to (System.currentTimeMillis() + j.optLong("expires_in", 3000) * 1000 - 60_000)
            t
        }
    }

    suspend fun channelTitle(): String = withContext(Dispatchers.IO) {
        g.http.newCall(Request.Builder().url("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
            .header("Authorization", "Bearer ${token()}").build()).execute().use { r ->
            val items = JSONObject(r.body?.string().orEmpty()).optJSONArray("items")
            if (items != null && items.length() > 0) items.getJSONObject(0).getJSONObject("snippet").getString("title") else ""
        }
    }
}

class RealYouTube(private val g: Graph, private val auth: YouTubeAuth) : VideoUploader {
    override suspend fun upload(
        video: File, title: String, description: String, tags: List<String>, thumb: File?, publishAtMs: Long, onProgress: (Float) -> Unit,
    ): UploadResult = withContext(Dispatchers.IO) {
        val s = g.settings.value
        val status = JSONObject().put("privacyStatus", "private").put("selfDeclaredMadeForKids", false).put("embeddable", true)
        if (s.ytAudited) {
            if (publishAtMs > System.currentTimeMillis() + 15 * 60_000) {
                status.put("publishAt", Instant.ofEpochMilli(publishAtMs).toString())
            } else status.put("privacyStatus", "public")
        }
        val tagList = JSONArray(); var total = 0
        for (t in tags) { if (total + t.length + 2 > 480) break; tagList.put(t); total += t.length + 2 }
        val meta = JSONObject().put("snippet", JSONObject().put("title", title.take(100)).put("description", description.take(4900))
            .put("tags", tagList).put("categoryId", s.ytCategory)).put("status", status)

        val init = Request.Builder().url("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
            .header("Authorization", "Bearer ${auth.token()}").header("X-Upload-Content-Type", "video/mp4")
            .header("X-Upload-Content-Length", video.length().toString())
            .post(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
        val session = g.http.newCall(init).execute().use { r ->
            if (!r.isSuccessful) throw mapError(r.code, r.body?.string().orEmpty())
            r.header("Location") ?: throw PublishException("YouTube didn't start the upload.")
        }
        val size = video.length()
        val chunk = 8L * 1024 * 1024
        var sent = 0L
        var resp: JSONObject? = null
        RandomAccessFile(video, "r").use { raf ->
            while (sent < size) {
                val n = minOf(chunk, size - sent).toInt()
                val buf = ByteArray(n); raf.seek(sent); raf.readFully(buf)
                val req = Request.Builder().url(session).header("Content-Range", "bytes $sent-${sent + n - 1}/$size")
                    .put(buf.toRequestBody("video/mp4".toMediaType())).build()
                var attempt = 0
                while (true) {
                    try {
                        g.http.newCall(req).execute().use { r ->
                            when (r.code) {
                                308 -> {}
                                200, 201 -> resp = JSONObject(r.body?.string().orEmpty())
                                else -> throw mapError(r.code, r.body?.string().orEmpty())
                            }
                        }
                        break
                    } catch (e: java.io.IOException) {
                        if (++attempt > 4) throw PublishException("The connection dropped during the YouTube upload.", "The app will retry.")
                        delay(2000L * attempt)
                    }
                }
                sent += n
                onProgress((sent.toFloat() / size).coerceAtMost(0.97f))
            }
        }
        val id = resp?.optString("id").orEmpty().ifBlank { throw PublishException("YouTube didn't confirm the upload.") }
        var thumbOk = false
        if (thumb != null && thumb.exists()) {
            val tr = Request.Builder().url("https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=$id")
                .header("Authorization", "Bearer ${auth.token()}").post(thumb.readBytes().toRequestBody("image/jpeg".toMediaType())).build()
            thumbOk = runCatching { g.http.newCall(tr).execute().use { it.isSuccessful } }.getOrDefault(false)
        }
        onProgress(1f)
        UploadResult(id, "https://youtu.be/$id", status.getString("privacyStatus"), thumbOk)
    }

    private fun mapError(code: Int, body: String): PublishException {
        val reason = runCatching { JSONObject(body).getJSONObject("error").getJSONArray("errors").getJSONObject(0).getString("reason") }.getOrDefault("")
        return when {
            reason in setOf("quotaExceeded", "dailyLimitExceeded") -> PublishException("YouTube's daily upload allowance is used up.", "It resets at midnight Pacific time.")
            reason == "uploadLimitExceeded" -> PublishException("This channel hit YouTube's upload limit.", "Try again tomorrow.")
            code == 401 || code == 403 -> PublishException("YouTube refused the upload.", "Reconnect YouTube in Settings → Accounts.", retry = false)
            else -> PublishException("YouTube upload failed ($code).", "The app will retry automatically.")
        }
    }
}

/** Used when publishing is off, and by tests: nothing leaves the phone. */
class FakeUploader : VideoUploader {
    val uploads = ArrayList<String>()
    override suspend fun upload(video: File, title: String, description: String, tags: List<String>, thumb: File?, publishAtMs: Long, onProgress: (Float) -> Unit): UploadResult {
        uploads.add(title); onProgress(1f)
        return UploadResult("fake${uploads.size}", "https://youtu.be/fake${uploads.size}", "private", thumb != null)
    }
}

