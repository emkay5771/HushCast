# HushCast `v0.1.8-hushcast`

### Performance & On-Device Analysis
- **ARM64 NEON Resampler & C++ FFT**: Moved audio resampling (`Resampler`) and spectral analysis (`Fft`) into native C++ (`-O2` + NEON `vfmaq_f32`), eliminating the 5%–25% `AudioDecoder` bottleneck on long episodes.
- **Faster Whisper & Smaller Models**: Disabled multi-temperature fallback retries (`temperature_inc = 0`), enabled built-in Silero VAD silence/music skipping during full transcription, switched to quantized `base.en q5_1` (58 MB) and `Qwen3-Embedding Q4_K_M` (395 MB) with `n_ctx = 2048`, and added big-core CPU thread selection + thermal status monitoring.
- **Performance & Battery Settings**: Added **Fast transcription model (`tiny.en`, 32 MB)**, **Fast timing mode (Flash Attention)**, and **Analyze only while charging** under **Settings → HushCast AI**.
- **Analysis Queue Control & Diagnostics**: Added a **Cancel** button on the analysis notification, a **Cancel Analysis Queue** button, and **Export HushCast Logs** (lightweight logcat + status export without needing a full Android bug report).
- **Faster Model Downloads**: Buffered 1 MB output + 256 KB reads in `Downloads.kt`.

### Pocket Casts UX & Grid Enhancements
- **Podcast Folders & Drag-and-Drop Sort**: Create folders (2×2 mini-grid cards), open/manage folders, and long-press drag-and-drop to reorder podcasts and folders in the Home grid.
- **Compact Grid & Grid Titles Toggle**: Added 4-column **Compact Grid**, fixed **Sort by New Episodes** (sorts by latest episode `pubDate`), downsampled grid cover art for smooth scrolling, and added a **Show titles in grid** toggle.
- **Playlists & New Episodes**: Converted Playlists/Filters to a Pocket Casts-style vertical list and fixed **New Episodes** to show unplayed episodes from subscribed podcasts.
- **Narrow Bottom Bar & Episode Detail**: Switched to a compact bottom navigation bar with tighter mini-player spacing and fixed the squished **Save** button on Episode Detail.
