# HushCast

![License](https://img.shields.io/github/license/emkay5771/HushCast?style=for-the-badge) ![GitHub release (latest by date)](https://img.shields.io/github/v/release/emkay5771/HushCast?style=for-the-badge)

**HushCast** is a privacy-first, open-source podcast app for **Android** (built in Kotlin & Jetpack Compose with **Material 3 Expressive** design, forked from [podium](https://github.com/aimok04/podium)). It adds a **100% on-device AI audio engine** that automatically mutes or micro-skips unwanted words/phrases and skips sponsor reads — with zero audio ever leaving your phone.

> [!NOTE]
> **First-time setup on your phone:** After installing the APK, open **Settings → HushCast** to download the on-device speech and ad-detection models over Wi-Fi.

---

## ✨ Features

### 🔇 On-Device Word & Profanity Muting
- **100% Local Transcription & Alignment**: Powered by `whisper.cpp` (with DTW word-level timestamps) and Silero VAD, plus fast-path support for Podcasting 2.0, SRT, and VTT transcripts with 30-second audio spot-check verification.
- **Three Mute Modes**:
  - **Hard Silence**: Cleanly mutes matched words with smooth 8ms cosine fade ramps.
  - **Volume Duck**: Drops volume to 10% during matched words.
  - **Sample-Drop Micro-Skip**: Cleanly splices out matched words with 12ms cosine crossfades.
- **Custom Dictionary & Presets**: Includes built-in **Profanity** and **Explicit Content** presets, masked-profanity matching (`f***`, `s**t`), plurals, compound phrases, and regex rules.

### ⏭️ On-Device Ad & Sponsor Skipping
- **Local Ad Detection**: Runs `llama.cpp` embeddings + an ONNX ad-tagger model sequentially after transcription to keep peak RAM low.
- **Auto-Skip with One-Tap Undo**: Automatically skips high-confidence sponsor segments and shows an instant **Undo** banner in the player if you want to jump back.
- **Annotated Scrubber**: Displays muted words and detected ad segments directly on the player timeline.

### 🎨 Material 3 Expressive & Player Experience
- **Adaptive & Themed Icon**: Supports Android 13+ Material You monochrome icon tinting.
- **Theme Options**: Choose **Automatic**, **Light**, or **Dark** mode, plus **Dynamic color (Material You)** and artwork-adaptive player colors.
- **Live-Synced Transcripts & Chapters**: Supports Podcasting 2.0 JSON chapters, Podlove Simple Chapters, and a live-scrolling transcript view.
- **Persistent Drag-to-Reorder Queue**: Long-press and drag episodes in **Next up**; your queue persists across app restarts.
- **Gradual Sleep Timer**: Fades playback volume down smoothly over the final 10 seconds.

### 🔍 Discovery, Sync & Playback
- **Podcast Discovery**: Search via **Podcast Index** and **Apple Podcasts**.
- **Sync & Android Auto**: Supports [**gpodder.net**](https://gpodder.net), [**nextcloud-gpodder**](https://github.com/thrillfall/nextcloud-gpodder), and **Android Auto**.
- **Background Analysis**: Automatically hashes downloaded episodes (SHA-256) and queues analysis while charging, or trigger **Analyze Now** manually.

---

## 📦 Installation

1. Go to the [**Releases**](https://github.com/emkay5771/HushCast/releases) page on GitHub.
2. Download the latest `HushCast-*.apk` on your Android phone (`arm64-v8a`, Android 8.0+ / API 26+).
3. Open the downloaded file and allow **Install unknown apps** if prompted.
4. Open **HushCast → Settings → HushCast** to download the on-device AI models and configure your mute/ad preferences.

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
