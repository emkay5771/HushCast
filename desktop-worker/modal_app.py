"""Modal serverless GPU deployment for HushCast Desktop/Cloud Worker.

Keeps local on-device Android and local Mac/LAN worker modes untouched while
providing an auto-scaling HTTPS GPU endpoint on Modal's free $30/mo tier.

Quickstart:
    pip install modal
    modal setup
    modal secret create hushcast-token HUSHCAST_WORKER_TOKEN="your-secret-token"
    modal run modal_app.py::setup_models
    modal deploy modal_app.py
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path

import modal

DATA_DIR = Path("/data")
MODELS_DIR = DATA_DIR / "models"
CACHE_DIR = DATA_DIR / "cache"
BUNDLED_AD_TAGGER = Path("/opt/hushcast/ad_tagger.onnx")

WHISPER_URL = (
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    "ggml-large-v3-turbo.bin"
)
QWEN_URL = (
    "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/"
    "Qwen3-Embedding-0.6B-Q8_0.gguf"
)

app = modal.App("hushcast-worker")
volume = modal.Volume.from_name("hushcast-data", create_if_missing=True)

_AD_TAGGER_URL = (
    "https://raw.githubusercontent.com/emkay5771/HushCast/main/"
    "core/automute-engine/src/main/assets/ad_tagger.onnx"
)
_LOCAL_AD_TAGGER_CANDIDATES = [
    Path(__file__).resolve().parent.parent / "core/automute-engine/src/main/assets/ad_tagger.onnx",
    Path(__file__).resolve().parent / "ad_tagger.onnx",
    Path.home() / ".hushcast-worker/models/ad_tagger.onnx",
]
_LOCAL_AD_TAGGER = next((p for p in _LOCAL_AD_TAGGER_CANDIDATES if p.exists()), None)

_base_image = (
    modal.Image.from_registry(
        "nvidia/cuda:12.4.1-devel-ubuntu22.04", add_python="3.11"
    )
    .apt_install("ffmpeg", "cmake", "build-essential", "git", "curl")
    .env({
        "GGML_CUDA": "1",
        "CMAKE_ARGS": "-DGGML_CUDA=1 -DCMAKE_CUDA_ARCHITECTURES=75;80;86;89",
    })
    .pip_install(
        "fastapi>=0.115.0",
        "uvicorn[standard]>=0.30.0",
        "httpx>=0.27.0",
        "pydantic>=2.7.0",
        "zeroconf>=0.132.0",
        "numpy>=1.26.0",
        "onnxruntime-gpu>=1.19.0",
        "av>=12.0.0",
    )
    .pip_install(
        "pywhispercpp>=1.2.0",
        extra_options="--no-binary pywhispercpp",
    )
    .pip_install(
        "llama-cpp-python>=0.3.0",
        extra_options="--extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu124",
    )
)
if _LOCAL_AD_TAGGER is not None:
    _base_image = _base_image.add_local_file(
        str(_LOCAL_AD_TAGGER),
        remote_path=str(BUNDLED_AD_TAGGER),
    )

image = _base_image.add_local_python_source("hushcast_worker")


def _ensure_models() -> None:
    import httpx

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)

    ad_dest = MODELS_DIR / "ad_tagger.onnx"
    if not ad_dest.exists() and BUNDLED_AD_TAGGER.exists():
        shutil.copy2(BUNDLED_AD_TAGGER, ad_dest)

    downloads = [
        (MODELS_DIR / "ggml-large-v3-turbo.bin", WHISPER_URL),
        (MODELS_DIR / "Qwen3-Embedding-0.6B-Q8_0.gguf", QWEN_URL),
    ]
    if not ad_dest.exists():
        downloads.append((ad_dest, _AD_TAGGER_URL))
    for dest, url in downloads:
        if dest.exists() and dest.stat().st_size > 0:
            continue
        tmp = dest.with_suffix(".part")
        print(f"Downloading {dest.name} from {url} ...")
        with httpx.stream("GET", url, follow_redirects=True, timeout=600.0) as resp:
            resp.raise_for_status()
            with tmp.open("wb") as f:
                for chunk in resp.iter_bytes(chunk_size=1024 * 1024):
                    f.write(chunk)
        tmp.replace(dest)
        print(f"Saved {dest} ({dest.stat().st_size / (1024 * 1024):.1f} MB)")


@app.function(
    image=image,
    volumes={str(DATA_DIR): volume},
    timeout=1800,
)
def setup_models() -> None:
    """One-time model download into the persistent Modal Volume."""
    _ensure_models()
    volume.commit()
    print("All HushCast models are ready in Modal Volume 'hushcast-data'.")


@app.function(
    image=image,
    gpu="T4",
    volumes={str(DATA_DIR): volume},
    secrets=[modal.Secret.from_name("hushcast-token")],
    max_containers=1,
    scaledown_window=120,
    timeout=900,
)
@modal.concurrent(max_inputs=10)
@modal.asgi_app()
def fastapi_app():
    from hushcast_worker.pipeline import LocalMlBackend
    from hushcast_worker.server import create_app
    from hushcast_worker.storage import WorkerCache

    _ensure_models()
    cache = WorkerCache(CACHE_DIR)
    backend = LocalMlBackend(MODELS_DIR)
    token = os.environ.get("HUSHCAST_WORKER_TOKEN") or None
    return create_app(cache=cache, backend=backend, token=token)


@app.local_entrypoint()
def pair_qr(url: str, token: str = "") -> None:
    """Print a scannable hushcast://pair QR code for your deployed Modal URL."""
    from hushcast_worker.cli import print_pairing_qr

    print(f"HushCast Modal Worker")
    print(f"  URL:           {url.strip()}")
    if token:
        print(f"  Pairing Token: {token}")
    print_pairing_qr(url, token)
