# HushCast Desktop Companion Worker (Phase 1)

A headless LAN companion server for **HushCast** that downloads podcast episodes on your Mac (or desktop GPU machine), runs `whisper.cpp` + `Qwen3-Embedding-0.6B` + `ad_tagger.onnx`, and serves **both the exact audio file and the pre-computed analysis bundle** keyed by `fileSha256` to your Android phone.

Because the Mac downloads the episode first and pushes the exact audio bytes + analysis to the phone, **Dynamic Ad Insertion (DAI) timestamps always match 100%**, and the phone never downloads the audio twice.

## Quick Start (macOS Apple Silicon)

```bash
cd desktop-worker
uv venv && source .venv/bin/activate
# Install with Metal-accelerated llama-cpp-python + whisper.cpp
CMAKE_ARGS="-DGGML_METAL=on" uv pip install -e ".[ml,dev]"
```

### Download Models into `~/.hushcast-worker/models/`

1. `ggml-base.en-q5_1.bin` — [HuggingFace](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin)
2. `Qwen3-Embedding-0.6B-Q8_0.gguf` — Qwen3-Embedding-0.6B GGUF
3. `ad_tagger.onnx` — automatically picked up from `../core/automute-engine/src/main/assets/ad_tagger.onnx`

### Run

```bash
hushcast-worker
```

It prints your LAN URL, pairing token, and advertises `_hushcast._tcp.local.` over Bonjour/mDNS so the HushCast Android app can discover it in **Settings → HushCast → Desktop Companion**.

## Running Tests

```bash
pytest
```

## Cloud Hosting (Modal Serverless GPU — Free Tier Friendly)

If you want to run the companion worker in the cloud (scales to zero when idle, ~$30/mo free credit on Modal) while still keeping local on-device Android and local Mac/LAN modes available:

```bash
cd desktop-worker
pip install modal
modal setup

# Lock your public HTTPS endpoint with a pairing token:
modal secret create hushcast-token HUSHCAST_WORKER_TOKEN="your-secret-token"

# One-time model download into persistent Modal Volume 'hushcast-data':
modal run modal_app.py::setup_models

# Deploy the HTTPS endpoint:
modal deploy modal_app.py
```

Paste the resulting `https://<user>--hushcast-worker-fastapi-app.modal.run` URL and `HUSHCAST_WORKER_TOKEN` into **Settings → HushCast → Desktop Companion** on your phone.

