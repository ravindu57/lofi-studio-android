package com.lofiautopilot.studio.publish

import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

data class FbPage(val id: String, val name: String, val token: String)

/** Facebook Page video publishing through the Graph API's resumable upload. */
class Facebook(private val g: Graph) {
    private val v = "v23.0"
    private val graph = "https://graph.facebook.com/$v"
    private val videoHost = "https://graph-video.facebook.com/$v"

    private fun err(code: Int, body: String): PublishException {
        val e = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val c = e?.optInt("code") ?: 0
        val msg = e?.optString("message").orEmpty().ifBlank { body.take(160) }
        return when {
            c == 190 -> PublishException("Facebook connection expired.", "Reconnect your Page in Settings → Accounts.", retry = false)
            c in setOf(10, 200, 270) || code == 403 -> PublishException("Facebook refused permission to post videos.", "Create the token with pages_manage_posts and pages_read_engagement.", retry = false)
            c == 368 -> PublishException("Facebook temporarily blocked posting.", "Wait a day before posting again.", retry = false)
            else -> PublishException("Facebook error: $msg", "The app will retry automatically.")
        }
    }

    private suspend fun post(url: String, form: Map<String, String>, file: Triple<String, String, ByteArray>? = null): JSONObject = withContext(Dispatchers.IO) {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        form.forEach { (k, x) -> b.addFormDataPart(k, x) }
        b.addFormDataPart("access_token", g.secrets.get(SecretStore.FB_TOKEN))
        file?.let { (field, name, bytes) -> b.addFormDataPart(field, name, bytes.toRequestBody("application/octet-stream".toMediaType())) }
        try {
            g.http.newCall(Request.Builder().url(url).post(b.build()).build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (r.code >= 400) throw err(r.code, t)
                JSONObject(t)
            }
        } catch (e: java.io.IOException) {
            throw PublishException("Couldn't reach Facebook.", "Check the internet connection; the app will retry.")
        }
    }

    /** Exchanges a short-lived user token for Page tokens that don't expire. */
    suspend fun pagesFromUserToken(appId: String, appSecret: String, shortToken: String): List<FbPage> = withContext(Dispatchers.IO) {
        val u1 = "$graph/oauth/access_token?grant_type=fb_exchange_token&client_id=$appId&client_secret=$appSecret&fb_exchange_token=$shortToken"
        val long = g.http.newCall(Request.Builder().url(u1).build()).execute().use { r ->
            val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw err(r.code, t); JSONObject(t).getString("access_token")
        }
        g.http.newCall(Request.Builder().url("$graph/me/accounts?fields=id,name,access_token&access_token=$long").build()).execute().use { r ->
            val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw err(r.code, t)
            val arr = JSONObject(t).getJSONArray("data")
            val pages = List(arr.length()) { arr.getJSONObject(it).let { p -> FbPage(p.getString("id"), p.getString("name"), p.getString("access_token")) } }
            if (pages.isEmpty()) throw PublishException("That account doesn't manage any Facebook Pages.", "Create a Page first.", retry = false)
            pages
        }
    }

    suspend fun checkPage(pageId: String): String = withContext(Dispatchers.IO) {
        g.http.newCall(Request.Builder().url("$graph/$pageId?fields=id,name&access_token=${g.secrets.get(SecretStore.FB_TOKEN)}").build()).execute().use { r ->
            val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw err(r.code, t); JSONObject(t).getString("name")
        }
    }

    suspend fun upload(video: File, title: String, description: String, thumb: File?, onProgress: (Float) -> Unit): UploadResult {
        val pageId = g.settings.value.fbPageId
        if (pageId.isBlank() || !g.secrets.has(SecretStore.FB_TOKEN)) throw PublishException("Facebook isn't connected.", "Connect your Page in Settings → Accounts.", retry = false)
        val url = "$videoHost/$pageId/videos"
        val size = video.length()
        val start = post(url, mapOf("upload_phase" to "start", "file_size" to size.toString()))
        val session = start.getString("upload_session_id")
        val vid = start.get("video_id").toString()
        var s = start.getString("start_offset").toLong(); var e = start.getString("end_offset").toLong()
        RandomAccessFile(video, "r").use { raf ->
            while (s < e) {
                val buf = ByteArray((e - s).toInt()); raf.seek(s); raf.readFully(buf)
                val res = post(url, mapOf("upload_phase" to "transfer", "upload_session_id" to session, "start_offset" to s.toString()), Triple("video_file_chunk", "chunk", buf))
                s = res.getString("start_offset").toLong(); e = res.getString("end_offset").toLong()
                onProgress(minOf(0.95f, s.toFloat() / size))
            }
        }
        val fin = mapOf("upload_phase" to "finish", "upload_session_id" to session, "title" to title.take(250), "description" to description, "published" to "true")
        val res = post(url, fin, thumb?.takeIf { it.exists() }?.let { Triple("thumb", "thumbnail.jpg", it.readBytes()) })
        if (!res.optBoolean("success", true)) throw PublishException("Facebook didn't accept the finished upload.")
        onProgress(1f)
        return UploadResult(vid, "https://www.facebook.com/$pageId/videos/$vid", "public", thumb != null)
    }
}
