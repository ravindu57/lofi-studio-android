package com.lofiautopilot.studio.ui.editor

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.lofiautopilot.studio.audio.BANDS
import com.lofiautopilot.studio.audio.FrameFeatures
import com.lofiautopilot.studio.audio.WAVE_N
import com.lofiautopilot.studio.export.Aspect
import com.lofiautopilot.studio.export.ImageLoader
import com.lofiautopilot.studio.export.TitleBitmap
import com.lofiautopilot.studio.gl.VfxRenderer
import com.lofiautopilot.studio.vfx.Intros
import com.lofiautopilot.studio.vfx.Look
import com.lofiautopilot.studio.vfx.MediaClip
import com.lofiautopilot.studio.vfx.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sin

/**
 * Live GPU preview of the project. It renders with the same code as the exporter, reading the playhead, the effect
 * clips and the analysed music straight from the [EditorModel]. With no music analysed it animates a demo signal.
 * The picture track can show two clips at once (current + outgoing) while a transition runs.
 */
class PreviewEngine(private val model: EditorModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var view: GLSurfaceView? = null
    private val ff = FrameFeatures()
    private val demoStart = System.nanoTime()
    private var cachedProject: Project? = null
    private var cachedLook: Look = Look()
    val broken = androidx.compose.runtime.mutableStateOf<Map<String, String>>(emptyMap())

    /** What one renderer slot is currently showing. */
    private class Src {
        var clipId: String? = null
        var uri: String? = null
        @Volatile var bmpUri: String? = null
        var player: MediaPlayer? = null
        var st: SurfaceTexture? = null
        var surf: Surface? = null
        var tex = 0
        @Volatile var frame = false
        @Volatile var ready = false
        @Volatile var gotFrame = false
    }
    private var src = arrayOf(Src(), Src())

    private val bitmaps = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 6
    }
    private val loading = HashSet<String>()

    fun attach(v: GLSurfaceView) { view = v }

    fun release() {
        for (s in src) stopPlayer(s)
        scope.cancel()
    }

    private fun stopPlayer(s: Src) {
        s.ready = false; s.gotFrame = false; s.frame = false
        val mp = s.player; s.player = null
        val surf = s.surf; val st = s.st
        s.surf = null; s.st = null
        main.post { runCatching { mp?.stop() }; runCatching { mp?.release() }; runCatching { surf?.release() }; runCatching { st?.release() } }
    }

    private fun look(p: Project): Look {
        if (p !== cachedProject) { cachedProject = p; cachedLook = p.look() }
        return cachedLook
    }

    private fun bitmapFor(uri: String): Bitmap? {
        synchronized(bitmaps) { bitmaps[uri]?.let { return it } }
        if (synchronized(loading) { loading.add(uri) }) {
            scope.launch {
                runCatching { ImageLoader.load(model.ctx, Uri.parse(uri), 1600) }.onSuccess { b -> synchronized(bitmaps) { bitmaps[uri] = b } }
                synchronized(loading) { loading.remove(uri) }
                view?.requestRender()
            }
        }
        return null
    }

    val renderer = object : GLSurfaceView.Renderer {
        private var vfx: VfxRenderer? = null
        private var sw = 1
        private var sh = 1
        private var rw = 0
        private var rh = 0

        private fun resetSources() {
            for (s in src) { stopPlayer(s); s.clipId = null; s.uri = null; s.bmpUri = null; s.tex = 0 }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            vfx = null; rw = 0; resetSources()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { sw = width; sh = height }

        private fun ensure(aspect: Aspect) {
            val w = if (aspect.w >= aspect.h) 960 else (540f * aspect.w / aspect.h).toInt() and 1.inv()
            val h = if (aspect.w >= aspect.h) (960f * aspect.h / aspect.w).toInt() and 1.inv() else 540
            if (vfx == null || w != rw || h != rh) {
                vfx?.release()
                resetSources()
                vfx = VfxRenderer(w, h).also { it.init() }
                rw = w; rh = h
            }
        }

        private fun swapSources(v: VfxRenderer) {
            val t = src[0]; src[0] = src[1]; src[1] = t
            v.swapSlots()
        }

        private fun release(i: Int, v: VfxRenderer) {
            stopPlayer(src[i]); src[i].clipId = null; src[i].uri = null; src[i].bmpUri = null; src[i].tex = 0
            v.clearSlot(i)
        }

        /** Makes slot [i] show [clip]. Pictures upload as soon as they are decoded; videos need a player. */
        private fun open(i: Int, clip: MediaClip, v: VfxRenderer) {
            val s = src[i]
            if (s.clipId == clip.id && s.uri == clip.uri) return
            stopPlayer(s)
            s.clipId = clip.id; s.uri = clip.uri; s.bmpUri = null
            when {
                clip.isIntro -> {
                    v.useGenerated(Intros.forUri(clip.uri)?.id ?: "pulse", clip.lenS, i)
                    v.setTitle(i, TitleBitmap.make(clip.title))
                }
                clip.video -> startVideo(i, clip, v)
                else -> { /* uploaded below when decoded */ }
            }
        }

        private fun startVideo(i: Int, clip: MediaClip, v: VfxRenderer) {
            val s = src[i]
            v.clearSlot(i)
            val tex = v.createExternalTexture()
            s.tex = tex
            val st = SurfaceTexture(tex)
            st.setOnFrameAvailableListener { s.frame = true; s.gotFrame = true; view?.requestRender() }
            s.st = st
            val surf = Surface(st)
            s.surf = surf
            main.post {
                val mp = MediaPlayer()
                try {
                    mp.setDataSource(model.ctx, Uri.parse(clip.uri))
                    mp.setSurface(surf)
                    mp.setVolume(0f, 0f)
                    mp.isLooping = true
                    mp.setOnPreparedListener { m ->
                        val w = m.videoWidth.coerceAtLeast(2); val h = m.videoHeight.coerceAtLeast(2)
                        view?.queueEvent { val idx = src.indexOf(s); if (idx >= 0 && s.tex == tex) vfx?.useExternal(tex, w, h, idx) }
                        s.ready = true
                    }
                    mp.setOnVideoSizeChangedListener { _, w, h ->
                        if (w > 0 && h > 0) view?.queueEvent { val idx = src.indexOf(s); if (idx >= 0 && s.tex == tex) vfx?.useExternal(tex, w, h, idx) }
                    }
                    mp.prepareAsync()
                    s.player = mp
                } catch (_: Exception) { runCatching { mp.release() } }
            }
        }

        private fun syncVideo(s: Src, clip: MediaClip, srcT: Float, playing: Boolean) {
            val mp = s.player ?: return
            if (!s.ready) return
            main.post {
                runCatching {
                    val d = if (clip.srcDurS > 0.1f) clip.srcDurS else mp.duration / 1000f
                    val want = if (d > 0.1f) (srcT % d) else srcT
                    if (playing && !mp.isPlaying) mp.start()
                    if (!playing && mp.isPlaying) mp.pause()
                    if (abs(mp.currentPosition / 1000f - want) > 0.45f) mp.seekTo((want * 1000).toInt())
                }
            }
        }

        private fun sourceReady(i: Int, clip: MediaClip): Boolean = when {
            clip.isIntro -> true
            clip.video -> src[i].ready && src[i].gotFrame
            else -> src[i].bmpUri == clip.uri
        }

        override fun onDrawFrame(gl: GL10?) {
            val p = model.project
            ensure(runCatching { Aspect.valueOf(p.aspect) }.getOrDefault(Aspect.WIDE))
            val v = vfx ?: return
            val t = model.posS
            val st = p.visualAt(t)
            if (st == null) {
                for (i in 0..1) if (src[i].clipId != null) release(i, v)
                v.transition = null
            } else {
                // keep clips where they are when they only change role (current <-> outgoing)
                if (src[0].clipId != st.cur.id && src[1].clipId == st.cur.id) swapSources(v)
                else if (st.prev != null && src[0].clipId == st.prev.id && src[1].clipId != st.prev.id) swapSources(v)
                open(0, st.cur, v)
                if (st.prev != null) open(1, st.prev, v) else if (src[1].clipId != null) release(1, v)
                val items = listOf(0 to (st.cur to st.curT), 1 to ((st.prev ?: st.cur) to st.prevT))
                for ((i, pair) in items) {
                    if (i == 1 && st.prev == null) continue
                    val (clip, srcT) = pair
                    val s = src[i]
                    when {
                        clip.isIntro -> v.setGenTime(i, srcT)
                        clip.video -> {
                            if (s.frame && s.st != null) {
                                s.frame = false
                                runCatching { s.st!!.updateTexImage(); val m = FloatArray(16); s.st!!.getTransformMatrix(m); v.setExternalMatrix(m, i) }
                            }
                            syncVideo(s, clip, srcT, model.playing)
                        }
                        else -> if (s.bmpUri != clip.uri) bitmapFor(clip.uri)?.let { b -> v.setImage(b, i); s.bmpUri = clip.uri }
                    }
                }
                val blend = st.prev != null && sourceReady(0, st.cur) && sourceReady(1, st.prev)
                v.transition = if (blend) st.transition to st.progress else null
            }
            v.look = look(p)
            fill(ff, t)
            v.render(t, ff, 0, sw, sh) { it.text }
            if (v.compileErrors.isNotEmpty() && broken.value.size != v.compileErrors.size) broken.value = HashMap(v.compileErrors)
        }
    }

    private fun fill(f: FrameFeatures, t: Float) {
        val ft = model.features
        if (ft != null && model.project.audio.isNotEmpty()) { ft.at(t, f); return }
        val tt = if (model.playing) t else (System.nanoTime() - demoStart) / 1e9f
        val beatT = (tt * 1.6f) % 1f
        val pulse = Math.exp((-beatT * 5f).toDouble()).toFloat()
        for (i in 0 until BANDS) {
            val x = i / (BANDS - 1f)
            val v = (0.18f + 0.55f * (1f - x * 0.7f) * (0.55f + 0.45f * sin(i * 0.55f + tt * 2.3f)) + 0.35f * pulse * (1f - x)).coerceIn(0f, 1f)
            f.spec[i] = (v * 255).toInt().toByte()
        }
        for (i in 0 until WAVE_N) f.wave[i] = ((0.5f + 0.38f * sin(i * 0.42f + tt * 5f) * (0.5f + 0.5f * pulse)) * 255).toInt().toByte()
        f.bass = 0.35f + 0.6f * pulse; f.mid = 0.4f + 0.2f * sin(tt * 1.3f); f.high = 0.35f + 0.2f * sin(tt * 2.1f + 1f)
        f.energy = 0.55f + 0.25f * sin(tt * 0.7f); f.beat = pulse; f.phase = beatT; f.direction = 1f
    }
}
