# HushCast

![License](https://img.shields.io/github/license/emkay5771/HushCast?style=for-the-badge) ![GitHub release (latest by date)](https://img.shields.io/github/v/release/emkay5771/HushCast?style=for-the-badge)

**HushCast** is a privacy-first, open-source podcast app for **Android** (built in Kotlin & Jetpack Compose with **Material 3 Expressive** design, forked from [podium](https://github.com/aimok04/podium)). It combines a **100% on-device AI audio engine** with an optional **Desktop / Cloud Companion Worker** (`desktop-worker`) to automatically mute/micro-skip unwanted words, skip sponsor reads, and generate transcripts, speaker labels, chapters, and summaries.

> [!NOTE]
> **First-time setup on your phone:** After installing the APK, open **Settings → HushCast** to either download the on-device AI models over Wi-Fi **or** pair a Desktop/Cloud Companion Worker via QR code or Bonjour/mDNS.

---

## ✨ Features

### 🔇 Word & Profanity Muting
- **On-Device Transcription & Alignment**: Powered by `whisper.cpp` (with DTW word-level timestamps) and Silero VAD, plus fast-path support for Podcasting 2.0, SRT, and VTT transcripts with 30-second audio spot-check verification.
- **Three Mute Modes**:
  - **Hard Silence**: Cleanly mutes matched words with smooth 8ms cosine fade ramps.
  - **Volume Duck**: Drops volume to 10% during matched words.
  - **Sample-Drop Micro-Skip**: Cleanly splices out matched words with 12ms cosine crossfades.
- **Custom Dictionary & Presets**: Includes built-in **Profanity** and **Explicit Content** presets, masked-profanity matching (`f***`, `s**t`), plurals, compound phrases, and regex rules.

### ⏭️ Ad & Sponsor Skipping
- **AI Ad Detection**: Runs `Qwen3-Embedding-0.6B` (`llama.cpp`) + an ONNX `ad_tagger.onnx` classifier sequentially after transcription to keep peak RAM low.
- **Auto-Skip with One-Tap Undo**: Automatically skips high-confidence sponsor segments and shows an instant **Undo** banner in the player if you want to jump back.
- **Annotated Scrubber**: Displays muted words and detected ad segments directly on the player timeline.

### 🖥️ Desktop / Cloud Companion Worker (`desktop-worker`)
- **Zero-Drift DAI Sync**: Your Mac (Apple Silicon Metal), desktop GPU (CUDA/CPU), or serverless [Modal](desktop-worker/README.md) GPU downloads the episode first, runs `whisper.cpp` (`large-v3-turbo` / `base.en`) + `Qwen3-Embedding-0.6B` + `ad_tagger.onnx`, and serves **both the exact audio bytes and the analysis bundle** keyed by `fileSha256` to your phone.
- **Easy Pairing & Discovery**: Advertises `_hushcast._tcp.local.` over Bonjour/mDNS and prints a scannable `hushcast://pair?...` QR code in the terminal.
- **Live CLI Progress & Cache Management**: Shows per-stage terminal progress bars and detailed logs, supports **Refresh on Desktop / Cloud** (`force=true` reprocessing), and includes `--clear-cache` / `--clear-audio-cache` (`DELETE /v1/cache`).
- **AI Episode Insights**:
  - **Speaker Diarization**: Acoustic MFCC + F0 pitch clustering labels speakers (`Speaker 1`, `Speaker 2`, …) in the synced transcript.
  - **Auto-Chapters & Summaries**: Topic-shift chapters with TF-IDF titles and sentence-level extractive summaries out of the box—or drop any Instruct GGUF (e.g. `Qwen2.5-1.5B-Instruct-Q4_K_M.gguf`) into `~/.hushcast-worker/models/` for full LLM summaries and chapters.
  - **Semantic Transcript Search**: Search across all desktop-analyzed episodes by meaning via `POST /v1/search`.

### 🎨 Material 3 Expressive & Player Experience
- **Processing & Download Clarity**: Episode cards display distinct processing badges (**`Desktop AI`**, **`Cloud AI`**, **`On-Device AI`**, **`Word-Mute`**, **`Audio Only`**), separate Downloaded vs. Listened icons, and persistent completion notifications when downloads/analysis finish.
- **Swipe & Multi-Select Actions**: Swipe to mark played/unplayed (with Undo), quick filter chips (**Unplayed**, **Downloaded**, **In Progress**), inline progress bars, and long-press multi-select for bulk **Play Next / Play Last / Mark Played**.
- **24-Hour Played Cache Protection**: Waits 24 hours after an episode is marked played before auto-deleting its cached audio.
- **Live-Synced Transcripts, Chapters & Theme**: Supports Podcasting 2.0 JSON chapters, Podlove Simple Chapters, a modal **Synced Transcript** with speaker labels, **Material You** dynamic & artwork-adaptive colors, and a **gradual 10s fade-out Sleep Timer**.

### 🔍 Discovery, Sync & Playback
- **Podcast Discovery & OPML Import**: Search via **Podcast Index** and **Apple Podcasts**, or import subscriptions from Pocket Casts and other apps (including nested/foldered `.opml` files).
- **Seamless Stream-to-Download Swap**: Start streaming immediately while downloading—playback swaps to the local file at the exact position as soon as the download completes.
- **Sync & Android Auto**: Supports [**gpodder.net**](https://gpodder.net), [**nextcloud-gpodder**](https://github.com/thrillfall/nextcloud-gpodder), and **Android Auto**.

---

## 📦 Installation

### Android App
1. Go to the [**Releases**](https://github.com/emkay5771/HushCast/releases) page on GitHub.
2. Download the latest `HushCast-*.apk` on your Android phone (`arm64-v8a`, Android 8.0+ / API 26+).
3. Open the downloaded file and allow **Install unknown apps** if prompted.
4. Open **HushCast → Settings → HushCast** to download the on-device AI models or pair your Desktop/Cloud Companion Worker.

### Desktop / Cloud Companion Worker (Optional)
See [`desktop-worker/README.md`](desktop-worker/README.md) for macOS Apple Silicon Metal setup, cache CLI flags, and Modal serverless GPU deployment.

---

## 🛠️ Building from Source

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
   ./gradlew :core:automute-engine:testDebugUnitTest :app:assembleDebug
   ```
   The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🙏 Attribution & License

- Built on [**podium**](https://github.com/aimok04/podium)
- Incorporates ideas and capabilities from [**AntennaPod**](https://github.com/AntennaPod/AntennaPod), [**podcast-ad-tagger**](https://github.com/Q726kbXuN/podcast-ad-tagger), [**whisper.cpp**](https://github.com/ggml-org/whisper.cpp), and [**llama.cpp**](https://github.com/ggml-org/llama.cpp)

Licensed under the [**GNU General Public License v3.0 or later**](/LICENSE).
