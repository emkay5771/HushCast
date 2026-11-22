# HushCast Desktop & Cloud Companion Worker

A headless LAN or cloud companion server for **HushCast** that downloads podcast episodes on your Mac (or desktop/cloud GPU machine), runs `whisper.cpp` + `Qwen3-Embedding-0.6B` + `ad_tagger.onnx`, and serves **both the exact audio file and the pre-computed analysis bundle** keyed by `fileSha256` to your Android phone.

Because the worker downloads the episode first and pushes the exact audio bytes + analysis to the phone, **Dynamic Ad Insertion (DAI) timestamps always match 100%**, and the phone never downloads the audio twice.

## Quick Start (macOS Apple Silicon)

```bash
cd desktop-worker
uv venv && source .venv/bin/activate
# Install with Metal-accelerated llama-cpp-python + whisper.cpp
CMAKE_ARGS="-DGGML_METAL=on" uv pip install -e ".[ml,dev]"
```

### Prerequisites
- Python 3.10+
- `ffmpeg` and `ffprobe` on `PATH` (for example `brew install ffmpeg` on macOS, or `sudo apt install ffmpeg` on Debian/Ubuntu; `ffprobe` is used to extract embedded podcast chapters).

### Download Models & Self-Test

```bash
hushcast-worker setup
```

This downloads `ggml-base.en-q5_1.bin` and `Qwen3-Embedding-0.6B-Q8_0.gguf` into `~/.hushcast-worker/models/` (the `--profile quality` uses `ggml-large-v3-turbo-q5_0.bin` and adds `Qwen2.5-3B-Instruct-Q4_K_M.gguf` for chapters and summaries). Every file is pinned to a fixed revision and checked against its SHA-256; an interrupted download resumes, and a file that fails the check is never put in place. When `torch` is installed, setup also fetches the MMS_FA word-alignment weights. It then copies `ad_tagger.onnx`, checks `ffmpeg`/`ffprobe` and the ML imports, and writes a launchd plist (macOS) or prints a systemd unit (Linux) so the worker can start at login.

### Run

```bash
hushcast-worker
```

It prints your LAN URL, pairing token, and advertises `_hushcast._tcp.local.` over Bonjour/mDNS so the HushCast Android app can discover it in **Settings → HushCast → Desktop / cloud worker**.

**HTTPS (optional):** `hushcast-worker --tls-cert cert.pem --tls-key key.pem` serves HTTPS and prints the certificate fingerprint to pin on the phone. Both flags are required together.

**Check a running worker:** `hushcast-worker health-check --url <worker URL>` checks `/v1/health` (status 200 and the current schema), retrying a few times. Add `--expect-deployment modal` for a Modal worker.

## Running Tests

```bash
pytest
```

## Cloud Hosting (Modal Serverless GPU — Free Tier Friendly)

If you want to run the companion worker in the cloud on an NVIDIA L4 GPU (24 GB VRAM, ~2× faster and ~36–39% cheaper per episode than T4, with automatic T4 fallback via `gpu=["L4", "T4"]`, scales to zero after 60s idle, up to 2 concurrent containers, ~$30/month free credit on [Modal](https://modal.com)), while still keeping local on-device Android and local Mac/LAN modes available:

### 1. Prerequisites
- A free [Modal](https://modal.com) account.
- Python 3.10+ and [`uv`](https://docs.astral.sh/uv/) (or `pip`).

### 2. Clone & Install Modal CLI

```bash
git clone https://github.com/emkay5771/HushCast.git
cd HushCast/desktop-worker

uv venv && source .venv/bin/activate
uv pip install modal qrcode
```

Authenticate your machine with Modal (opens your browser):

```bash
uv run modal setup
```

### 3. Create Your Pairing Token Secret

Generate a strong random pairing token and store it in a Modal Secret named **`hushcast-token`** (this protects `/v1/jobs`, `/v1/episodes`, and `/v1/search` on your public Modal URL):

```bash
TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
echo "Save this token: $TOKEN"

uv run modal secret create hushcast-token HUSHCAST_WORKER_TOKEN="$TOKEN"
```

> **Note:** Keep `$TOKEN` saved somewhere safe (e.g. a password manager) — you will enter it on your phone or pass it to `pair_qr` below. Make sure the secret key is named **`HUSHCAST_WORKER_TOKEN`** and created in the same Modal environment (`main`) you deploy to.

### 4. Download Models into Persistent Modal Storage (One-Time)

This downloads the pinned models into a persistent Modal Volume named `hushcast-data`: `faster-whisper-large-v3-turbo` (~1.6 GB, the default speech-to-text), `ggml-large-v3-turbo.bin` (~1.55 GB, whisper.cpp fallback), `Qwen3-Embedding-0.6B-Q8_0.gguf` (~610 MB), `Qwen2.5-3B-Instruct-Q4_K_M.gguf` (~1.9 GB, for chapters and summaries) and `ad_tagger.onnx`. Each file is checked against its SHA-256 once and recorded, so later cold starts only check file sizes. Running this right after a deploy also saves the first cold start from doing the downloads and checks itself:

```bash
uv run modal run modal_app.py::setup_models
```

### 5. Deploy the HTTPS Worker

```bash
uv run modal deploy modal_app.py
```

Modal will print your permanent production URL, which looks like:

```text
https://<your-modal-username>--hushcast-worker-fastapi-app.modal.run
```

*(Make sure you use the deployed `.modal.run` URL from `modal deploy`, **not** a temporary `-dev.modal.run` URL from `modal serve` or `modal run`.)*

### 6. Pair Your Android Phone

**Option A — Scan QR Code (Easiest):**
Print a scannable `hushcast://pair?...` QR code in your terminal using your deployed URL and token (make sure `$TOKEN` is still set in your shell):

```bash
uv run modal run modal_app.py::pair_qr \
  --url "https://<your-modal-username>--hushcast-worker-fastapi-app.modal.run" \
  --token "$TOKEN"
```

On your phone:
1. Open **HushCast → Settings → HushCast**.
2. Enable **Desktop / cloud worker**.
3. Tap **Scan QR** (or scan with your phone's Camera app).
4. Tap **Test** — you should see `Connected: ... (CUDA) • Models Ready`.

**Option B — Manual Entry:**
1. Open **HushCast → Settings → HushCast** and enable **Desktop / cloud worker**.
2. Paste your deployed `https://<your-modal-username>--hushcast-worker-fastapi-app.modal.run` URL into **Worker URL**.
3. Paste your `$TOKEN` into **Pairing Token**.
4. Tap **Test** to verify both connectivity and token authentication.

### Notes & Troubleshooting
- **Verify auth directly with `curl`:** `/v1/health` is unauthenticated, while `/v1/episodes` and `/v1/jobs` require your token. You can verify both your URL and token from your terminal:
  ```bash
  curl -i -H "Authorization: Bearer $TOKEN" \
    "https://<your-modal-username>--hushcast-worker-fastapi-app.modal.run/v1/episodes"
  ```
  It should return `HTTP/2 200` (`[]` if no episodes are cached yet).
- **Rotating or updating `hushcast-token` (`HTTP 401` after `modal secret create --force`):** If you update `hushcast-token` without changing `modal_app.py`, `uv run modal deploy modal_app.py` may print `Deployment skipped: no changes detected!` and keep existing containers running with the old token. Stop the app first so new containers pick up the updated secret:
  ```bash
  uv run modal secret create --force hushcast-token HUSHCAST_WORKER_TOKEN="$TOKEN"
  uv run modal app stop hushcast-worker
  uv run modal deploy modal_app.py
  ```
  You can also run `uv run modal secret list` to confirm `hushcast-token` is in the active environment.
- **Cold starts (scale-to-zero):** To save credits, Modal scales the GPU container down after 1 minute of idle time (`scaledown_window=60`). The first **Test** or episode download after idle may take ~15–25 seconds (longer if L4 provisioning falls back or queues) while the GPU container boots; subsequent requests while warm are instant.
- **Auto-deploy via GitHub Actions:** Pushing changes under `desktop-worker/` to `main` automatically deploys to Modal via [`.github/workflows/deploy-modal.yml`](../.github/workflows/deploy-modal.yml) once you add **`MODAL_TOKEN_ID`** and **`MODAL_TOKEN_SECRET`** (from [Modal Settings → API Tokens](https://modal.com/settings/tokens)) to your GitHub repository's **Settings → Secrets and variables → Actions**. After deploying, the workflow runs `hushcast-worker health-check` against the deployed URL. It uses the optional **`MODAL_WORKER_URL`** secret if you set one, otherwise the URL printed by `modal deploy`; if neither is available the check is skipped with a warning.
- **Updating the worker manually:** After pulling new changes (`git pull origin main`), re-run `uv run modal deploy modal_app.py` inside `desktop-worker/`.
- **Optional LLM Chat / Summaries:** If you also want an instruct GGUF model on Modal, upload or download it into `/data/models/` inside the `hushcast-data` volume.



