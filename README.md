# HushCast

![License](https://img.shields.io/github/license/emkay5771/HushCast?style=for-the-badge) ![GitHub release (latest by date)](https://img.shields.io/github/v/release/emkay5771/HushCast?style=for-the-badge)

**HushCast** is a privacy-first, open-source podcast app for **Android** (built in Kotlin & Jetpack Compose with **Material 3 Expressive** design, forked from [podium](https://github.com/aimok04/podium)). It combines a **100% on-device AI audio engine** with an optional **Desktop / Cloud Companion Worker** (`desktop-worker`) to automatically mute/micro-skip unwanted words, skip sponsor reads, and generate transcripts, speaker labels, chapters, and summaries.

> [!NOTE]
> **First-time setup on your phone:** After installing the APK, open **Settings → HushCast** to either download the on-device AI models over Wi-Fi **or** pair a Desktop/Cloud Companion Worker via QR code or Bonjour/mDNS.

---

## ⚡ Choosing a Processing Method (Practical Comparison)

HushCast can analyze episodes in three ways. You can switch or combine them anytime in **Settings → HushCast** (under **Automatic downloads**, choose **Auto** to prefer your paired worker and fall back to your phone, **On-device** to always use your phone, or **Skip** to download audio without analyzing).

| What matters to you | 📱 On-Device (Phone Only) | 🖥️ Desktop Worker (Your Mac / PC) | ☁️ Cloud Worker (Modal) |
| :--- | :--- | :--- | :--- |
| **Setup** | **Easiest.** Tap once in Settings to download ~455 MB of models. No computer or account needed. | **Moderate.** Run a small background app on your Mac or PC and scan a QR code. | **Most setup.** Requires a free Modal account and a one-time terminal deploy, then scan a QR code. |
| **Speed** | **Slowest** (~several minutes per hour of audio on phone CPU). | **Fast** (usually under a minute per episode on Apple Silicon or a desktop GPU). | **Fastest** once warm (tens of seconds on a cloud GPU; ~15–25s cold start if idle). |
| **Phone battery & heat** | **Uses noticeable battery** and warms the phone during analysis (best left for overnight charging). | **Zero battery impact** on your phone—your computer does all the work. | **Zero battery impact** on your phone—the cloud GPU does all the work. |
| **Word & ad accuracy** | **Good** for everyday English podcasts and sponsor skipping. | **Better / Best** (supports larger speech models and fewer missed words or cutoffs). | **Best** (uses the largest speech model by default). |
| **Extra AI perks** | Word muting, ad skipping, and searchable transcript. | Adds **speaker labels**, **auto-generated chapters**, **episode summaries**, and **search by meaning**. | Adds **speaker labels**, **auto-generated chapters**, **episode summaries**, and **search by meaning**. |
| **Offline & privacy** | **100% private & offline.** Audio never leaves your phone. | **100% private on your home network.** Works over Wi-Fi without external cloud servers. | **Private to your own Modal account**, but requires internet and sends episode URLs to your Modal server. |
| **Cost** | **Free.** | **Free** (uses your own computer). | **Free tier friendly** (uses Modal's ~$30/month free credit; scales to zero when idle). |

---

## ✨ Features

### 🔇 Word & Profanity Muting
- **On-Device Transcription & Alignment**: Powered by `whisper.cpp` (with DTW word-level timestamps) and Silero VAD, plus fast-path support for Podcasting 2.0, SRT, and VTT transcripts with 30-second audio spot-check verification.
- **Five Mute Modes**:
  - **Hard Silence**: Cleanly mutes matched words with smooth 8ms cosine fade ramps.
  - **Volume Duck**: Drops volume to 10% during matched words.
  - **Sample-Drop Micro-Skip**: Cleanly splices out matched words with 12ms cosine crossfades.
  - **Bleep**: Replaces matched words with a classic 1 kHz censor tone.
  - **Custom Sound Effect**: Pick any local audio file to play over matched words.
- **Custom Dictionary & Presets**: Includes built-in **Profanity** and **Explicit Content** presets, masked-profanity matching (`f***`, `s**t`), plurals, compound phrases, and regex rules.

### ⏭️ Ad & Sponsor Skipping
- **AI Ad Detection**: Runs `Qwen3-Embedding-0.6B` (`llama.cpp`) + an ONNX `ad_tagger.onnx` classifier sequentially after transcription to keep peak RAM low.
- **Auto-Skip with Indicator & One-Tap Undo**: Automatically skips high-confidence sponsor segments, optionally plays a **Sponsor Skip Indicator** chime (or custom sound), and shows an instant **Undo** banner in the player without shifting playback controls.
- **Annotated Scrubber & Miniplayer Markers**: Displays detected ad spans and muted-word ticks directly on both the full-screen scrubber and the miniplayer's bottom progress bar, with collapsed sponsor pills in the transcript view.

### 🖥️ Desktop / Cloud Companion Worker (`desktop-worker`)
- **Zero-Drift DAI Sync**: Your Mac (Apple Silicon Metal), desktop GPU (CUDA/CPU), or serverless [Modal](desktop-worker/README.md) GPU downloads the episode first, runs `whisper.cpp` (`large-v3-turbo` / `base.en`) + `Qwen3-Embedding-0.6B` + `ad_tagger.onnx`, and serves **both the exact audio bytes and the analysis bundle** keyed by `fileSha256` to your phone.
- **Easy Pairing & Discovery**: Advertises `_hushcast._tcp.local.` over Bonjour/mDNS and prints a scannable `hushcast://pair?...` QR code in the terminal.
- **Live CLI Progress & Cache Management**: Shows per-stage terminal progress bars and detailed logs, supports **Refresh on Desktop / Cloud** (`force=true` reprocessing), and includes `--clear-cache` / `--clear-audio-cache` (`DELETE /v1/cache`).
- **AI Episode Insights**:
  - **Speaker Diarization**: Acoustic MFCC + F0 pitch clustering labels speakers (`Speaker 1`, `Speaker 2`, …) in the synced transcript.
  - **Grounded Auto-Chapters & Summaries**: Embedding topic-shift boundaries lock chapters to exact transcript timestamps across the full episode, with TF-IDF or local LLM (`Qwen2.5-3B-Instruct-Q4_K_M.gguf`) titles and episode summaries.
  - **Semantic Transcript Search**: Search across all desktop-analyzed episodes by meaning via `POST /v1/search`.

### 🎨 Material 3 Expressive & Player Experience
- **Built on Compose 1.13 & Material 3 1.5.0 Stable**: Uses M3 Expressive theming, unified `ConnectedSingleChoiceGroup` controls, shape-morphing Play/Pause & Skip buttons with spring feedback, and native Compose 1.13 progressive blur on podcast header backdrops.
- **Pocket Casts-Style M3E Miniplayer**: Uncluttered floating miniplayer with rounded-square artwork, centered Skip Back / Play-Pause / Skip Forward controls, a queue count pill, and blocked-content markers on the bottom progress bar.
- **Tabbed Full-Screen Player**: Smooth `HorizontalPager` across **Player**, **Transcript**, **Chapters**, and **Notes** with a swipe-following tab indicator, swipe-up to open **Up Next** (with swipe-to-remove **Undo**), and a **customizable action bar** (up to 4 shortcuts + **More** overflow).
- **Playback Effects**: Speed `-`/`+` stepper & presets (`0.5x`–`3.0x`), **Trim Silence**, **Volume Boost**, and **speed- & skip-adjusted remaining time** (e.g., `-24:10 (-16:07)`).
- **System Media Player Speed Badge**: Android's system media notification displays the live playback speed value (`1.0x`, `1.2x`, `1.5x`, etc.) and updates immediately when cycled.
- **Predictive Back & Swipe Actions**: Native Android predictive back animations across all navigation routes, detail panes, and Settings sub-panes; Pocket Casts-style half-swipe 2-option trays and full-swipe primary triggers on episode lists; long-press multi-select for bulk **Play Next / Play Last / Mark Played**.
- **Processing & Download Clarity**: Distinct processing badges (**`Desktop AI`**, **`Cloud AI`**, **`On-Device AI`**, **`Word-Mute`**, **`Audio Only`**), 24-hour played cache protection, high-contrast dark-mode transcript highlighting, **Material You** artwork-adaptive colors, and a **10s fade-out Sleep Timer**.

### 🔍 Discovery, Sync & Playback
- **Podcast Discovery & OPML Import**: Search via **Podcast Index** and **Apple Podcasts**, or import subscriptions from Pocket Casts and other apps (including nested/foldered `.opml` files).
- **Seamless Stream-to-Download Swap**: Start streaming immediately while downloading—playback swaps to the local file at the exact position as soon as the download completes.
- **Sync & Android Auto**: Supports [**gpodder.net**](https://gpodder.net), [**nextcloud-gpodder**](https://github.com/thrillfall/nextcloud-gpodder), and **Android Auto**.

---

## 📦 Installation

### Android App
1. Go to the [**Releases**](https://github.com/emkay5771/HushCast/releases) page on GitHub.
2. Download the latest `HushCast-*.apk` on your Android phone (`arm64-v8a`, **Android 14+ / API 34+**).
3. Open the downloaded file and allow **Install unknown apps** if prompted.
4. Open **HushCast → Settings → HushCast** to download the on-device AI models or pair your Desktop/Cloud Companion Worker.

### Desktop / Cloud Companion Worker (Optional)
See [`desktop-worker/README.md`](desktop-worker/README.md) for macOS Apple Silicon Metal setup, cache CLI flags, and Modal serverless GPU deployment.

---

## 🛠️ Building from Source

Requires **JDK 17+** and **Android SDK 37** (`platforms;android-37.0`, `build-tools;37.0.0`, `ndk;27.2.12479018`, `cmake;3.22.1`).

1. Clone the repository:
   ```bash
   git clone https://github.com/emkay5771/HushCast.git
   cd HushCast
   ```
2. Create a `local.properties` file in the project root (already gitignored) with your Android SDK path and optional [Podcast Index](https://api.podcastindex.org/) API credentials:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   PODCAST_INDEX_API_KEY=your_key_here
   PODCAST_INDEX_API_SECRET=your_secret_here
   ```
3. Run unit tests and build the debug APK:
   ```bash
   ./gradlew :core:automute-engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
   ```
   The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🙏 Attribution & License

- Built on [**podium**](https://github.com/aimok04/podium)
- Incorporates ideas and capabilities from [**AntennaPod**](https://github.com/AntennaPod/AntennaPod), [**podcast-ad-tagger**](https://github.com/Q726kbXuN/podcast-ad-tagger), [**whisper.cpp**](https://github.com/ggml-org/whisper.cpp), and [**llama.cpp**](https://github.com/ggml-org/llama.cpp)

Licensed under the [**GNU General Public License v3.0 or later**](/LICENSE).
