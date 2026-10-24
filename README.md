# AIM3 (Android app)

The whole Lofi Autopilot on your phone: it picks music and your newest picture, adds audio-reactive effects, renders the video with the phone's hardware encoder, and can publish it on a schedule. No computer needed. (The old `android/` folder is only a remote control for the PC version.)

Install: copy `LofiAutopilot-Studio.apk` (in the main folder) to the phone and open it. Build it yourself with `make studio-apk` from `code/`.

## What's inside
| Part | What it does |
|---|---|
| **Home** | Autopilot orb (on/off), stats, set-up checklist, current render with pause/cancel, new pictures, recent videos |
| **Studio** | Projects list. Opening one switches to a **landscape timeline editor** (immersive, system bars hidden): picture or video track, music clips with waveforms, effect clips in lanes. Add songs and effects manually and place them exactly: one finger scrolls (fling), two fingers pinch-zoom, tap selects, long-press then drag moves a clip (magnetic snapping to the playhead, clip edges, project end and beats, with haptic ticks), 48 dp trim handles on selected clips, split at the playhead, duplicate, delete, undo/redo, autosave. Tools appear only for what is selected (contextual toolbar); adjustments, effects, looks, music, format, export and **Auto AI** open as bottom panels so the preview stays visible |
| **Videos** | Every job, details, edit title/description/tags, play, share, retry |
| **Library** | Music folders; **watched picture folder** (new pictures are detected, used as the artwork or as references for the default prompt) |
| **Assistant** | Chat that manages and fixes the app with tools (status, logs, retry, schedule, settings, designs looks). Offline fallback uses the on-device model |
| **Settings** | Schedule, video defaults, AI, YouTube/Facebook, notifications, battery help |

The old Pipeline map screen is gone.

## How the automation works
1. A foreground service (notification stays visible) checks the picture folder and queues the day's videos `lead hours` before the publish time.
2. Plan: tracks (never the same order twice) + title/description/tags from rules, the on-device model, or the AI.
3. Artwork: newest unused picture (or generated from references with your default prompt if an OpenAI key is set).
4. Listen: decode and analyse the mix (spectrum, bass/mids/treble, beats, tempo, loudness per track normalised to −14 LUFS).
5. Direct: the rhythm director chooses a look and a loud/quiet curve. With the on-device model it reads a numeric summary of the music; without it, rules do. It does not "hear" audio; it reads the analysis.
6. Render, thumbnail, save to Gallery (Movies/LofiStudio), publish (off by default: practice mode).

Files made by Studio and Autopilot are in `Android/data/com.lofiautopilot.studio/files/videos/`.

## Set-up notes
- **On-device AI**: Settings → AI → download Qwen 2.5 (0.5B or 1.5B, no account needed) or Gemma (needs a free Hugging Face token). Runs with MediaPipe on the phone.
- **Cloud AI (free)**: Settings → AI. Gemini is the default (free key from aistudio.google.com/apikey, model `gemini-2.5-flash`); Groq (console.groq.com/keys) and OpenRouter (openrouter.ai/keys) also have free keys. Keys for the others are used as automatic backups when one hits its free limit. Only short text is sent, never audio or pictures.
- **YouTube**: create a Google Cloud OAuth client of type "TVs and Limited Input devices", enable YouTube Data API, paste client ID/secret, tap Connect and enter the code. Until Google audits your API project uploads are private.
- **Facebook**: app ID/secret + a short user token → "Find my Pages".
- **Notifications**: local notifications always; optional ntfy.sh, Discord webhook and Telegram when you're away. True server push (Firebase) isn't used because nothing runs on a server.
- **Oppo / ColorOS**: allow background activity and auto-launch for the app and lock it in Recents, or the system may stop Autopilot. If it does, a notification asks you to tap to resume (Android doesn't allow restarting it silently after a reboot).

## Developer notes
- Kotlin + Compose; GLSL ES 3.0 effects in `vfx/`, one renderer (`gl/VfxRenderer.kt`) for preview and export; `export/VideoExporter.kt` encodes with MediaCodec.
- `src/debug/.../SelfTestActivity` renders every effect and runs an export/job on a device or emulator (`adb shell am start -n com.lofiautopilot.studio/.debug.SelfTestActivity --es mode fx|export|job`).
- Building needs ~3 GB RAM free: `gradle --stop` afterwards, and don't run the emulator at the same time on a small machine.

## Timeline editor and AI arrangement
- **Auto AI** (editor) and automatic videos use the same arranger: it listens to the music, finds quiet / building / loud sections and places effect clips on the timeline (calm effects in quiet parts, visualizers when it plays, build-ups, short accents at big changes). Rules always work. With **Settings → AI → On-device AI** a small local model chooses the style and the effects per section; with **Cloud AI** it does the same with a stronger model. AI-placed clips are tagged, so re-arranging replaces only them and keeps your own.
- The assistant (chat tab) can also work on the open project: `get_project`, `add_effect_clip`, `add_music_clip`, `auto_arrange`, `clear_effects`, `design_look`.
- Design: Vercel/Geist look (Geist and Geist Mono bundled, OFL), black/white with hairline borders; the editor is always dark.

## v1.1 additions
- **AIM3**: new name and logo (target ring, three equaliser bars, one accent dot).
- **Audio tools**: per-clip five-band equalizer with presets (bass boost, warm lofi, muffled tape, vocal…), a Fade panel with presets and visible ramps on the clip, split at the playhead, volume. EQ plays live in the preview and is applied in the export.
- **Panels** in the editor can be dragged taller/shorter or expanded to full screen; the fullscreen button shows the preview full screen.
- **Pet**: a small cat on the Home screen that wanders, naps, grooms and hops, reacts to taps, and wears headphones while a video renders.
- **Discover**: a YouTube-style feed of new videos for your keywords (needs a free YouTube Data API key in Settings). Trending / Latest / Rising fast, plus trending ideas pulled from titles; tap one to inspire your next automatic video.
- **Export** also offers 360p.
- **On-device AI safety**: runs on the CPU only, and if it ever takes the app down it is switched off automatically (re-enable in Settings → AI).

## v1.2 additions
- **Pet**: now a shaded, lit 3D-style cat that floats over the Home screen (no row of its own). Drag it anywhere and drop it (it falls and bounces), tap to pet. It **evolves** with the number of videos: Kitten → Cat → DJ Cat (permanent headphones) → Legend (crown and glow).
- **Dashboard** (chart icon on Home): videos made, hours, success rate, streak, 14-day chart, weekly goal, plain-language insights with shortcuts, render time and storage, library moods and most-used songs, favourite effects, and YouTube views/likes of what you published (needs the YouTube Data API key).
- **Autopilot animation**: radar ripples, orbiting satellites with comet trails and a progress ring around the AIM3 mark.
- **Home**: next-video countdown, quick switches (practice/live publishing with confirmation, AI director, save to Gallery, thumbnail title), weekly goal, continue-editing card, idea of the day from Discover, low-storage warning with one-tap clean-up.

## v1.3: style prompt
- **Auto AI** in the editor has a prompt box: describe the look ("rainy night, purple glow on the bass, calm and dreamy") and pick who decides: Rules (keywords), On-device AI, or Cloud AI. The result is effect clips and colours placed on the timeline according to the music, and a note says exactly what was used (or why it fell back).
- Keyword rules always run, so the prompt works offline too; AI answers are merged on top. The on-device model gets one tiny question (style, a few effect names, colours) that fits its small context window; cloud AI also chooses effects section by section.
- On-device AI is now only switched off after a real native crash (not after swiping the app away).
- The assistant tool `auto_arrange` accepts `style_request`.

## v1.4: media track, transitions, intros, spectrum hills
- **Several pictures and videos in one project.** The *Media* tool adds pictures/videos at the playhead (pick many at once, or tick several from the watched folder in the order you want). Clips sit back to back; drag the handles to trim, long-press and drag to reorder, **Split** cuts a clip at the playhead, Duplicate/Delete ripple the track. If the music is longer than the media, the last picture holds.
- **Transitions.** Tap the **＋** between two clips (or select a clip → *Transition*) to blend them: crossfade, dip to black, light flash, slides, cover, wipe, blinds, iris, zoom, zoom blur, spin, pixelate, glitch, noise dissolve, ripple, beat pulse. Length is adjustable, a pick plays straight away, and *All cuts* applies it everywhere. Transitions are GLSL shaders that mix the outgoing and incoming clip, so videos keep playing through the cut; preview and export use the same code.
- **Built-in intros.** Five procedural intro clips (Neon Grid, Vinyl Dawn, Aurora Night, Rainy City, Pulse Rings) with your project name as the title. They are drawn by shaders, so there are no video files to ship and they render at any resolution. An intro takes over the first seconds, it does not lengthen the video.
- **Spectrum hills.** Four new visualizers: *Spectrum Hills* (layered misty mountain ranges, rim light and haze), *Neon Terrain* (synthwave wireframe mountains and striped sun), *Ridge Lines* (stacked pulsar spectrum lines) and *Moonlit Valley* (moon, stars, lake). The old filled spectrum is now *Spectrum Area*.
- **Automation.** *Auto AI → Cut to the music* rotates your pictures on section changes (snapped to beats) and picks a transition per cut from the energy there. Automatic videos can use several pictures from the watched folder (*Settings → Automatic videos → Pictures per video*) and an intro. The arranger now uses the new hills visualizers; the prompt understands words like "mountains", "terrain", "moonlit lake".
- **Timeline scrolling.** Quick swipes travel up to 4× further than slow ones, flings coast much longer, and dragging the ruler scrubs the whole project edge to edge.
