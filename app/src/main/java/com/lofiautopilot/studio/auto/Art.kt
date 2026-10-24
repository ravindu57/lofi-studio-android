package com.lofiautopilot.studio.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.Base64
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import com.lofiautopilot.studio.data.ImageEntity
import com.lofiautopilot.studio.export.ImageLoader
import com.lofiautopilot.studio.publish.PublishException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class ArtException(msg: String, val hint: String = "") : Exception(msg)

data class ArtChoice(val file: File, val usedImages: List<ImageEntity>, val note: String)

/** Picks (or generates) the artwork for a video from the pictures in the watched folder. */
class Art(private val ctx: Context, private val g: Graph) {
    private fun dir() = File(ctx.filesDir, "art").apply { mkdirs() }

    /**
     * DIRECT mode: newest unused picture becomes the artwork.
     * REFERENCE mode: new pictures steer the default prompt through the image model; falls back to DIRECT without a key.
     */
    suspend fun choose(jobId: Long, prompt: String): ArtChoice {
        val s = g.settings.value
        val images = g.db.images()
        val fresh = images.newOnes()
        val all = images.candidates()
        val openAiKey = g.secrets.get(SecretStore.OPENAI)

        if (s.imageMode == "REFERENCE" && openAiKey.isNotBlank()) {
            val refs = (fresh + all.filter { it !in fresh }).distinctBy { it.id }.take(s.refsPerPrompt.coerceIn(0, 8))
            try {
                val bytes = generate(openAiKey, prompt, refs)
                val out = File(dir(), "job-%04d.png".format(jobId))
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ArtException("The image service returned something that isn't a picture.")
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                return ArtChoice(out, refs, "Generated from ${refs.size} reference picture(s) with your default prompt.")
            } catch (e: ArtException) {
                g.log("Picture generation failed (${e.message}); using a picture from the folder instead.", jobId, "warn")
            } catch (e: java.io.IOException) {
                g.log("Couldn't reach the image service; using a picture from the folder instead.", jobId, "warn")
            }
        } else if (s.imageMode == "REFERENCE") {
            g.log("Reference mode needs an OpenAI key; using your newest picture directly.", jobId, "warn")
        }

        val pick = fresh.firstOrNull() ?: all.firstOrNull()
        if (pick == null) {
            if (openAiKey.isNotBlank()) {
                val bytes = try { generate(openAiKey, prompt, emptyList()) } catch (e: java.io.IOException) { throw ArtException("Couldn't reach the image service.", "Check the internet connection.") }
                val out = File(dir(), "job-%04d.png".format(jobId))
                out.writeBytes(bytes)
                return ArtChoice(out, emptyList(), "Generated from the default prompt (no pictures in the folder yet).")
            }
            throw ArtException("There are no pictures to use.", "Choose a picture folder in Library and save a picture into it.")
        }
        val out = File(dir(), "job-%04d.jpg".format(jobId))
        val bmp = ImageLoader.load(ctx, Uri.parse(pick.uri), 3072)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        bmp.recycle()
        return ArtChoice(out, listOf(pick), if (pick.state == "new") "Using the new picture “${pick.name}”." else "No new pictures, reusing “${pick.name}”.")
    }

    suspend fun markUsed(list: List<ImageEntity>) {
        val dao = g.db.images()
        for (i in list) dao.byUri(i.uri)?.let { dao.update(it.copy(state = "used", usedCount = it.usedCount + 1, lastUsedAt = System.currentTimeMillis())) }
    }

    private suspend fun generate(key: String, prompt: String, refs: List<ImageEntity>): ByteArray = withContext(Dispatchers.IO) {
        val model = "gpt-image-1"
        val req = if (refs.isNotEmpty()) {
            val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model).addFormDataPart("size", "1536x1024")
                .addFormDataPart("prompt", "$prompt\nUse the reference images only as a guide to art style, palette and mood. Create an original composition; do not copy any reference.")
            refs.forEachIndexed { i, r ->
                val bmp = ImageLoader.load(ctx, Uri.parse(r.uri), 1280)
                val bos = java.io.ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos); bmp.recycle()
                b.addFormDataPart("image[]", "ref$i.jpg", bos.toByteArray().toRequestBody("image/jpeg".toMediaType()))
            }
            Request.Builder().url("https://api.openai.com/v1/images/edits").header("Authorization", "Bearer $key").post(b.build()).build()
        } else {
            val body = JSONObject().put("model", model).put("prompt", prompt).put("size", "1536x1024").toString()
            Request.Builder().url("https://api.openai.com/v1/images/generations").header("Authorization", "Bearer $key")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        }
        g.http.newBuilder().readTimeout(java.time.Duration.ofMinutes(3)).build().newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(t).getJSONObject("error").getString("message") }.getOrDefault("error ${r.code}")
                throw ArtException("The image service said: $msg", if (r.code == 401) "Check the OpenAI key in Settings → AI." else "")
            }
            val b64 = JSONObject(t).getJSONArray("data").getJSONObject(0).optString("b64_json")
            if (b64.isBlank()) throw ArtException("The image service returned no picture.")
            Base64.decode(b64, Base64.DEFAULT)
        }
    }
}

object Thumbnailer {
    /** 1280x720 JPEG under 2 MB, cropped from the artwork, optionally with the title. */
    fun make(artwork: File, out: File, text: String): File {
        val src = android.graphics.BitmapFactory.decodeFile(artwork.absolutePath) ?: throw ArtException("Couldn't open the artwork for the thumbnail.")
        val w = 1280; val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (w / scale); val sh = (h / scale)
        val l = (src.width - sw) / 2; val t = (src.height - sh) / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(src, Rect(l.toInt(), t.toInt(), (l + sw).toInt(), (t + sh).toInt()), RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
        src.recycle()
        if (text.isNotBlank()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 245, 230); textSize = 64f; typeface = Typeface.DEFAULT_BOLD }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, (w * 0.78f).toInt())
                .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            val top = h - 60f - layout.height
            val bg = Paint().apply { color = Color.argb(130, 10, 12, 24) }
            c.drawRoundRect(RectF(40f, top - 24f, w * 0.9f, h - 36f), 18f, 18f, bg)
            c.save(); c.translate(68f, top); layout.draw(c); c.restore()
        }
        out.parentFile?.mkdirs()
        for (q in intArrayOf(92, 85, 78, 70, 60)) {
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }
            if (out.length() <= 2 * 1024 * 1024) break
        }
        bmp.recycle()
        return out
    }
}

@Suppress("unused")
private fun unusedPublish() = PublishException("")
