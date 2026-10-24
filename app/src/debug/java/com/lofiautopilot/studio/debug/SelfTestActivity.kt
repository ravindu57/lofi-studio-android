package com.lofiautopilot.studio.debug

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES30
import android.os.Bundle
import android.util.Log
import com.lofiautopilot.studio.audio.AudioAnalysis
import com.lofiautopilot.studio.audio.FrameFeatures
import com.lofiautopilot.studio.audio.PcmDecoder
import com.lofiautopilot.studio.export.Codec
import com.lofiautopilot.studio.export.ExportRequest
import com.lofiautopilot.studio.export.ImageLoader
import com.lofiautopilot.studio.export.VideoExporter
import com.lofiautopilot.studio.gl.EglCore
import com.lofiautopilot.studio.gl.VfxRenderer
import com.lofiautopilot.studio.vfx.EffectLibrary
import com.lofiautopilot.studio.vfx.FxKind
import com.lofiautopilot.studio.vfx.LayerFactory
import com.lofiautopilot.studio.vfx.Look
import com.lofiautopilot.studio.vfx.Presets
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Developer-only: exercises the GL engine and exporter on a device or emulator and writes results to files/selftest. */
class SelfTestActivity : Activity() {
    private val tag = "SelfTest"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "all"
        thread(name = "selftest") {
            val dir = File(getExternalFilesDir(null), "selftest").apply { mkdirs() }
            val log = File(dir, "log.txt")
            log.writeText("")
            fun say(s: String) { Log.i(tag, s); log.appendText(s + "\n") }
            try {
                if (mode == "all" || mode == "fx") fxSheet(dir, ::say)
                if (mode == "all" || mode == "export") export(dir, ::say)
                if (mode == "job") job(::say)
                if (mode == "arrange") arrange(::say)
                say("DONE")
            } catch (t: Throwable) {
                Log.e(tag, "failed", t)
                say("FAILED: $t\n" + Log.getStackTraceString(t))
            }
            runOnUiThread { finish() }
        }
    }

    private fun synthetic(f: FrameFeatures, t: Float) {
        for (i in f.spec.indices) {
            val x = i / 63f
            val v = (0.85f * (1f - x * 0.8f) * (0.6f + 0.4f * sin(i * 0.7f + t * 3f))).coerceIn(0f, 1f)
            f.spec[i] = (v * 255).toInt().toByte()
        }
        for (i in f.wave.indices) f.wave[i] = ((0.5f + 0.4f * sin(i * 0.5f + t * 6f)) * 255).toInt().toByte()
        f.bass = 0.75f; f.mid = 0.5f; f.high = 0.4f; f.energy = 0.7f; f.beat = 0.7f; f.phase = 0.25f; f.direction = 1f
    }

    private fun fxSheet(dir: File, say: (String) -> Unit) {
        val img = File(getExternalFilesDir(null), "test.png")
        val egl = EglCore()
        val surf = egl.createPbuffer(16, 16)
        egl.makeCurrent(surf)
        val w = 480; val h = 270
        val r = VfxRenderer(w, h).also { it.init() }
        val bmp = ImageLoader.load(this, Uri.fromFile(img), 1280)
        r.setImage(bmp)
        val errors = r.compileAll()
        say("shader errors: ${errors.size}")
        errors.forEach { (k, v) -> say("  $k: ${v.take(600)}") }
        val f = FrameFeatures()
        val out = File(dir, "fx").apply { mkdirs() }
        // target FBO: a texture we can read back
        val tex = com.lofiautopilot.studio.gl.GlUtil.tex2d(w, h)
        val fbo = com.lofiautopilot.studio.gl.GlUtil.fbo(tex)
        for (d in EffectLibrary.all) {
            r.look = Look("t", listOf(LayerFactory.create(d)))
            for (frame in 0 until 2) {
                synthetic(f, 2f + frame * 0.04f)
                r.render(2.3f + frame * 0.04f, f, fbo, w, h)
            }
            val b = r.readPixels(fbo, w, h)
            FileOutputStream(File(out, "${d.id}.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        for (p in Presets.builtin) {
            r.look = p
            synthetic(f, 2f)
            r.render(2.3f, f, fbo, w, h)
            val b = r.readPixels(fbo, w, h)
            FileOutputStream(File(out, "preset_${p.name.replace(' ', '_')}.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        // built-in intros, then every transition halfway between a generated intro and the test picture
        val introDir = File(dir, "intro").apply { mkdirs() }
        r.look = Look("t")
        for (i in com.lofiautopilot.studio.vfx.Intros.all) {
            r.useGenerated(i.id, i.lenS, 0)
            r.setTitle(0, com.lofiautopilot.studio.export.TitleBitmap.make("AIM3 Lofi"))
            r.setGenTime(0, 2.4f)
            synthetic(f, 2.4f)
            r.render(2.4f, f, fbo, w, h)
            val b = r.readPixels(fbo, w, h)
            FileOutputStream(File(introDir, "${i.id}.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        r.setImage(bmp, 0)
        r.useGenerated("neongrid", 5f, 1)
        r.setGenTime(1, 2f)
        for (t in com.lofiautopilot.studio.vfx.Transitions.all) {
            r.transition = t.id to com.lofiautopilot.studio.vfx.Transitions.ease(0.5f)
            synthetic(f, 2.4f)
            r.render(2.4f, f, fbo, w, h)
            val b = r.readPixels(fbo, w, h)
            FileOutputStream(File(introDir, "tr_${t.id}.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        r.transition = null
        say("rendered ${EffectLibrary.all.size} effects + ${Presets.builtin.size} presets; palette accent=${Integer.toHexString(r.palette.accent)}")
        r.release(); egl.release()
    }

    private fun arrange(say: (String) -> Unit) {
        val g = com.lofiautopilot.studio.core.Graph.get(this)
        val base = getExternalFilesDir(null)!!
        val f = base.listFiles { x -> x.extension == "mp3" }!!.sortedBy { it.name }.first()
        val d = PcmDecoder.probe(this, Uri.fromFile(f)) / 1e6f
        val clip = com.lofiautopilot.studio.vfx.AudioClip(uri = Uri.fromFile(f).toString(), title = "A", srcDurS = d, startS = 0f, inS = 20f, lenS = 90f)
        val project = com.lofiautopilot.studio.vfx.Project(name = "t", media = listOf(com.lofiautopilot.studio.vfx.MediaClip(uri = Uri.fromFile(File(base, "test.png")).toString(), lenS = 20f)), audio = listOf(clip))
        val an = AudioAnalysis.run(this, project.audio, true, null)
        say("analysed ${an.features.durationS}s bpm=${an.features.bpm}")
        g.settings.update { it.copy(planner = "rules") }
        for (prompt in listOf("", "rainy night, purple glow, calm and dreamy", "neon club with pink and blue lasers, energetic", "retro synthwave sunset, vhs")) {
            val r = kotlinx.coroutines.runBlocking { g.engine.arranger.arrange(project, an.features, true, prompt) }
            say("PROMPT '$prompt' -> style=${r.plan.style} by=${r.by} clips=${r.layers.size} notes=${r.notes}")
            say("   " + r.layers.joinToString { "${it.fx}[${"%.0f".format(it.from)}-${"%.0f".format(if (it.to < 0) 90f else it.to)}]${if (it.c1 != null) "#" + Integer.toHexString(it.c1!!).takeLast(6) else ""}" })
        }
        // small-model reply parsing, including messy answers
        for (a in listOf("{\"style\":\"night\",\"fx\":[\"bars\",\"neon\",\"bloom\"],\"colors\":[\"#9b5cff\"],\"intensity\":1.1}", "Sure! Style: dreamy. I would use bokeh, dreamglow and fog with #ff88cc", "I don't know")) {
            say("PARSE '${a.take(50)}' -> ${com.lofiautopilot.studio.ai.StylePrompt.parseLocal(a)}")
        }
    }

    private fun job(say: (String) -> Unit) {
        val g = com.lofiautopilot.studio.core.Graph.get(this)
        val base = getExternalFilesDir(null)!!
        kotlinx.coroutines.runBlocking {
            g.settings.update { it.copy(quality = 540, fps = 12, targetMinutes = 1, lookName = "Auto", directorOn = true, saveToGallery = true, publishEnabled = false) }
            for (f in base.listFiles { x -> x.extension == "mp3" }!!.sortedBy { it.name }) {
                val u = Uri.fromFile(f)
                g.db.tracks().insert(com.lofiautopilot.studio.data.TrackEntity(uri = u.toString(), title = f.nameWithoutExtension + " slowed reverb", durationMs = PcmDecoder.probe(this@SelfTestActivity, u) / 1000, moods = "chill"))
            }
            g.db.images().insert(com.lofiautopilot.studio.data.ImageEntity(uri = Uri.fromFile(File(base, "test.png")).toString(), name = "test.png"))
            val id = g.engine.createJob("manual", minutes = 1)
            say("job $id created")
            val t0 = System.currentTimeMillis()
            g.engine.onProgress = { j -> say("  [${(System.currentTimeMillis() - t0) / 1000}s] ${j.stage} ${(j.progress * 100).toInt()}% ${j.phase}") }
            g.engine.runJob(id)
            val j = g.db.jobs().get(id)!!
            say("RESULT status=${j.status} stage=${j.stage} title='${j.title}' out=${j.outPath} thumb=${j.thumbPath} gallery=${j.savedToGallery} err='${j.error}' ${j.hint}")
            say("size=" + File(j.outPath).length() / 1024 + " KiB")
            g.db.logs().recentNow(30).reversed().forEach { say("LOG ${it.text}") }
        }
    }

    private fun export(dir: File, say: (String) -> Unit) {
        val base = getExternalFilesDir(null)!!
        val mp3 = base.listFiles { f -> f.extension in setOf("mp3", "m4a", "wav", "ogg") }?.sortedBy { it.name } ?: emptyList()
        if (mp3.size < 2) { say("need two audio files in $base"); return }
        val dur = mp3.map { PcmDecoder.probe(this, Uri.fromFile(it)) / 1e6f }
        // a timeline: trimmed clip A (30s..42s), clip B overlapping it from 8s with fades, effects placed at set times
        val a = com.lofiautopilot.studio.vfx.AudioClip(uri = Uri.fromFile(mp3[0]).toString(), title = "A", srcDurS = dur[0], startS = 0f, inS = 30f, lenS = 12f, fadeOutS = 4f)
        val b = com.lofiautopilot.studio.vfx.AudioClip(uri = Uri.fromFile(mp3[1]).toString(), title = "B", srcDurS = dur[1], startS = 8f, inS = 5f, lenS = 12f, fadeInS = 4f, fadeOutS = 2f)
        fun fx(id: String, from: Float, to: Float, track: Int) = LayerFactory.create(EffectLibrary.get(id)!!).copy(from = from, to = to, track = track)
        val project = com.lofiautopilot.studio.vfx.Project(
            name = "t", media = run {
                val img = Uri.fromFile(File(base, "test.png")).toString()
                listOf(
                    com.lofiautopilot.studio.vfx.MediaClip(uri = "intro:neongrid", lenS = 4f, title = "AIM3 test"),
                    com.lofiautopilot.studio.vfx.MediaClip(uri = img, lenS = 8f, transition = "dip_black", transS = 1f),
                    com.lofiautopilot.studio.vfx.MediaClip(uri = img, lenS = 8f, transition = "glitch", transS = 0.8f),
                ).fold(emptyList<com.lofiautopilot.studio.vfx.MediaClip>()) { acc, c -> acc + c.copy(startS = acc.lastOrNull()?.endS ?: 0f) }
            }, audio = listOf(a, b),
            effects = listOf(fx("cam_float", 0f, -1f, 0), fx("viz_bars", 0f, 8f, 1), fx("viz_circle", 8f, 20f, 1), fx("gl_chroma", 6f, 10f, 2), fx("fx_snow", 12f, 20f, 3)),
        )
        val t0 = System.currentTimeMillis()
        val an = AudioAnalysis.run(this, project.audio, true, null)
        say("analysis ${System.currentTimeMillis() - t0} ms; duration=${an.features.durationS}s bpm=${an.features.bpm} beats=${an.features.beatTimes.size} lufs=${an.lufs}")
        val out = File(dir, "export.mp4")
        val w = intent.getIntExtra("w", 960); val h = intent.getIntExtra("h", 540)
        val req = ExportRequest(project.media, project.look(), project.audio.map { it.copy(lufs = an.lufs[it.id]) }, true, w, h, 12,
            VideoExporter.bitrateFor(w, h, 12, Codec.H264, true), Codec.H264, out, null)
        val t1 = System.currentTimeMillis()
        val res = VideoExporter(this).export(req, an, { }, { false })
        say("export ${System.currentTimeMillis() - t1} ms for ${res.seconds}s (${res.frames} frames) -> ${out.length() / 1024} KiB")
    }
}
