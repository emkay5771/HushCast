"""Modal serverless GPU deployment for HushCast Desktop/Cloud Worker.

Architecture (V1 / B10 / V2):
- CPU-only `@modal.asgi_app` serves API, long-polling, search, and cached bundle/audio downloads.
- `@app.cls(gpu="T4")` loads ML models once in `@modal.enter` and runs jobs via `.spawn()`.
- Job state is shared via `modal.Dict("hushcast-jobs")` so polling never keeps the GPU warm
  and jobs survive client disconnects or web container scale-downs.

Quickstart:
    pip install modal
    modal setup
    modal secret create hushcast-token HUSHCAST_WORKER_TOKEN="your-secret-token"
    modal run modal_app.py::setup_models
    modal deploy modal_app.py
"""

import hashlib
import json
import logging
import os
import shlex
import shutil
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

import modal

DATA_DIR = Path("/data")
MODELS_DIR = DATA_DIR / "models"
CACHE_DIR = DATA_DIR / "cache"
BUNDLED_AD_TAGGER = Path("/opt/hushcast/ad_tagger.onnx")

# ---------------------------------------------------------------------------------------------
# V19: every model is pinned to an immutable revision and checked against its SHA-256.
# Digests come from the Hugging Face API (`/api/models/<repo>/revision/<sha>?blobs=true`,
# field lfs.sha256) or, for small non-LFS files, from hashing the file at that revision.
# ---------------------------------------------------------------------------------------------
_HF = "https://huggingface.co"
WHISPER_CPP_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
QWEN_EMBED_REVISION = "370f27d7550e0def9b39c1f16d3fbaa13aa67728"
QWEN_CHAT_REVISION = "f302c64a2269a69fb27b2f9473b362f5bb8e78d8"
# faster-whisper's "large-v3-turbo" alias points at this repo, which now redirects to
# dropbox-dash/faster-whisper-large-v3-turbo (same commits). Files are fetched from the
# redirect target but stored under the name faster-whisper and huggingface_hub use.
FASTER_WHISPER_MODEL_ID = "large-v3-turbo"
FASTER_WHISPER_REPO = "mobiuslabsgmbh/faster-whisper-large-v3-turbo"
FASTER_WHISPER_DOWNLOAD_REPO = "dropbox-dash/faster-whisper-large-v3-turbo"
FASTER_WHISPER_REVISION = "0a363e9161cbc7ed1431c9597a8ceaf0c4f78fcf"
AD_TAGGER_REF = "v1.0.30-hushcast"

WHISPER_URL = f"{_HF}/ggerganov/whisper.cpp/resolve/{WHISPER_CPP_REVISION}/ggml-large-v3-turbo.bin"
QWEN_URL = (
    f"{_HF}/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/{QWEN_EMBED_REVISION}/"
    "Qwen3-Embedding-0.6B-Q8_0.gguf"
)
# W7: Smaller 3B instruct model (~2 GB vs 4.7 GB) with JSON mode
CHAT_URL = (
    f"{_HF}/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/{QWEN_CHAT_REVISION}/"
    "Qwen2.5-3B-Instruct-Q4_K_M.gguf"
)
_AD_TAGGER_URL = (
    f"https://raw.githubusercontent.com/emkay5771/HushCast/{AD_TAGGER_REF}/"
    "model/ad_tagger.onnx"
)
MMS_FA_URL = "https://dl.fbaipublicfiles.com/mms/torchaudio/ctc_alignment_mling_uroman/model.pt"

# Hugging Face cache layout under the `download_root` pipeline.py gives faster-whisper.
FASTER_WHISPER_CACHE = Path("faster-whisper") / f"models--{FASTER_WHISPER_REPO.replace('/', '--')}"
FASTER_WHISPER_SNAPSHOT = FASTER_WHISPER_CACHE / "snapshots" / FASTER_WHISPER_REVISION


@dataclass(frozen=True)
class PinnedFile:
    path: str  # destination, relative to MODELS_DIR
    url: str
    sha256: str
    size: int
    required: bool = True


def _faster_whisper_file(name: str, sha256: str, size: int) -> PinnedFile:
    return PinnedFile(
        str(FASTER_WHISPER_SNAPSHOT / name),
        f"{_HF}/{FASTER_WHISPER_DOWNLOAD_REPO}/resolve/{FASTER_WHISPER_REVISION}/{name}",
        sha256,
        size,
    )


MODEL_FILES: tuple[PinnedFile, ...] = (
    PinnedFile(
        "ggml-large-v3-turbo.bin", WHISPER_URL,
        "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69", 1624555275,
    ),
    PinnedFile(
        "Qwen3-Embedding-0.6B-Q8_0.gguf", QWEN_URL,
        "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439", 639150592,
    ),
    PinnedFile(
        "Qwen2.5-3B-Instruct-Q4_K_M.gguf", CHAT_URL,
        "9c9f56a391a3abbd5b89d0245bf6106081bcc3173119d4229235dd9d23253f94", 1929903264,
    ),
    _faster_whisper_file("config.json", "b0253ea6c0d3bea6b1e19e91a02acfd3b53f4467362efcb5a3e6b16c9b3a9b7e", 2263),
    _faster_whisper_file("preprocessor_config.json", "7ccc62c6f2765af1f3b46c00c9b5894426835a05021c8b9c01eecb6dfb542711", 340),
    _faster_whisper_file("tokenizer.json", "297b13372ac43916285644fb9687add3cc62ee2a1adb60da3dc25cc94c1871fd", 2710337),
    _faster_whisper_file("vocabulary.json", "c69260f2ab26d659b7c398f9a2b2b48ed0df16c3b47d7326782fd9cba71690c1", 1068114),
    _faster_whisper_file("model.bin", "e76620f83d5f5b69efd3d87e3dc180c1bd21df9fbebacfd4335e5e1efcc018da", 1617884929),
    # V7: MMS_FA forced-alignment weights, where torch.hub looks for them under TORCH_HOME.
    PinnedFile(
        "torch/hub/checkpoints/model.pt", MMS_FA_URL,
        "20ef12963ab4924bef49ac4fc7f58ad5da2ee43b2c11bc8c853c9b90ecdbc680", 1262047414,
        required=False,
    ),
)
AD_TAGGER_FILE = PinnedFile(
    "ad_tagger.onnx", _AD_TAGGER_URL,
    "c58f1db750e9148b337311642af0bdcf5a352dc9984c1cd2c8f197cdbc4bd351", 11867080,
)

# Job and health timing (module-level so tests can shorten them).
QUEUED_STALE_SEC = 1200.0  # 3a: a job may wait this long for GPU capacity before it's "stale"
RUNNING_STALE_SEC = 120.0  # no heartbeat for this long while running = the GPU container died
HEARTBEAT_SEC = 5.0
HEARTBEAT_DOWNLOAD_SEC = 2.0  # 3c: shorter while downloading, so progress shows up promptly
GPU_HEALTH_REFRESH_SEC = 30.0
GPU_WARM_SEC = 150.0
GPU_STATS_TIMEOUT_SEC = 3.0

app = modal.App("hushcast-worker")
volume = modal.Volume.from_name("hushcast-data", create_if_missing=True)
job_dict = modal.Dict.from_name("hushcast-jobs", create_if_missing=True)

_LOCAL_AD_TAGGER_CANDIDATES = [
    Path(__file__).resolve().parent.parent / "model/ad_tagger.onnx",
    Path(__file__).resolve().parent.parent / "core/automute-engine/src/main/assets/ad_tagger.onnx",
    Path(__file__).resolve().parent / "ad_tagger.onnx",
    Path.home() / ".hushcast-worker/models/ad_tagger.onnx",
]
_LOCAL_AD_TAGGER = next((p for p in _LOCAL_AD_TAGGER_CANDIDATES if p.exists()), None)

# W11: exact versions from uv.lock for Python 3.11, the Python of both images. The lock also
# resolves other versions for Python 3.10 and 3.12+; those don't apply here.
# tests/test_modal_app.py::test_image_pins_match_uv_lock keeps these in step with the lock.
CPU_IMAGE_PINS: tuple[str, ...] = (
    "fastapi==0.163.1",
    "starlette==1.10.0",
    "uvicorn[standard]==0.54.0",
    "httpx==0.28.1",
    "pydantic==2.14.3",
    "zeroconf==0.154.0",
    "numpy==2.4.6",
    "onnxruntime==1.32.0",
    "qrcode==8.2",
)
PYWHISPERCPP_VERSION = "1.5.1"
LLAMA_CPP_PYTHON_VERSION = "0.3.37"
ONNXRUNTIME_GPU_VERSION = "1.20.2"  # GPU build of onnxruntime (uv.lock only has the CPU package)
GPU_IMAGE_PINS: tuple[str, ...] = (
    "faster-whisper==1.2.1",  # not in uv.lock (GPU-only)
    "fastapi==0.163.1",
    "starlette==1.10.0",
    "uvicorn[standard]==0.54.0",
    "httpx==0.28.1",
    "pydantic==2.14.3",
    "zeroconf==0.154.0",
    "numpy==2.4.6",
    f"onnxruntime-gpu=={ONNXRUNTIME_GPU_VERSION}",
    "av==18.1.0",
    "qrcode==8.2",
)


def _pin_map(pins: tuple[str, ...]) -> dict[str, str]:
    out: dict[str, str] = {}
    for pin in pins:
        name, _, version = pin.partition("==")
        out[name.split("[")[0]] = version
    return out


# V19 / W11: fail the image build if any pinned package isn't the installed version, e.g.
# when a later pip step upgrades numpy, or llama-cpp-python came from a stale wheel.
_GPU_IMPORT_CHECK = (
    "import importlib.metadata as md; "
    "import av, numpy, onnxruntime, llama_cpp, torch, torchaudio, faster_whisper; "
    "from pywhispercpp.model import Model; "
    "providers = onnxruntime.get_available_providers(); "
    'assert "CUDAExecutionProvider" in providers, f"Missing CUDAExecutionProvider: {providers}"; '
    f'assert llama_cpp.__version__ == "{LLAMA_CPP_PYTHON_VERSION}", '
    f'f"llama-cpp-python {{llama_cpp.__version__}} != {LLAMA_CPP_PYTHON_VERSION}"; '
    f"pins = {json.dumps(_pin_map(GPU_IMAGE_PINS + (f'pywhispercpp=={PYWHISPERCPP_VERSION}',)))}; "
    "bad = {k: md.version(k) for k, v in pins.items() if md.version(k) != v}; "
    'assert not bad, f"Installed versions differ from the pins: {bad}"; '
    'print("ML imports OK", providers, "llama_cpp", llama_cpp.__version__)'
)

# V19 / W11: Lightweight CPU image aligned with uv.lock for fast API/polling/audio serving cold starts
_cpu_image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install(*CPU_IMAGE_PINS)
    .add_local_python_source("hushcast_worker")
)

# V14 / V19 / W3 / W11: Heavy CUDA builds ordered first, app source last
_gpu_base_image = (
    modal.Image.from_registry(
        "nvidia/cuda:12.4.1-devel-ubuntu22.04", add_python="3.11"
    )
    .apt_install("ffmpeg", "cmake", "build-essential", "git", "curl")
    .env({
        "GGML_CUDA": "1",
        "CMAKE_ARGS": "-DGGML_CUDA=1 -DCMAKE_CUDA_ARCHITECTURES=75;89 -DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "CMAKE_POLICY_VERSION_MINIMUM": "3.5",
        "TORCH_HOME": "/data/models/torch",
        "HUSHCAST_ASR_ENGINE": "faster-whisper",
    })
    .pip_install(
        f"pywhispercpp=={PYWHISPERCPP_VERSION}",
        extra_options="--no-binary pywhispercpp",
    )
    # Item 2 / W11: llama-cpp-python 0.3.37 sets n_seq_max for embedding contexts, which batched
    # embeddings need. The cu124 wheel index stops at 0.3.19, so build 0.3.37 from the PyPI sdist
    # against this image's CUDA toolkit (nvcc, cmake 3.22, gcc from build-essential; ninja for speed).
    # GGML_NATIVE=OFF keeps the CPU code portable (AVX2, no -march=native from the build host),
    # like the prebuilt wheel was; the linker flag lets anything that links libggml-cuda resolve
    # libcuda.so.1 at runtime, since the build host has only the driver stub.
    .apt_install("ninja-build")
    .run_commands(
        'CC=gcc CXX=g++ CMAKE_ARGS="$CMAKE_ARGS -DGGML_NATIVE=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF '
        '-DCMAKE_EXE_LINKER_FLAGS=-Wl,--allow-shlib-undefined" '
        f"pip install --no-cache-dir --no-binary llama-cpp-python llama-cpp-python=={LLAMA_CPP_PYTHON_VERSION}",
    )
    .pip_install(
        "torch==2.5.1",
        "torchaudio==2.5.1",
        extra_options="--index-url https://download.pytorch.org/whl/cu124",
    )
    .pip_install(*GPU_IMAGE_PINS)
    .run_commands(
        f"pip uninstall -y onnxruntime && pip install --no-deps --force-reinstall onnxruntime-gpu=={ONNXRUNTIME_GPU_VERSION}",
        "find /usr/local/lib/python3.11/site-packages -name 'libwhisper*.so*' -o -name 'libggml*.so*' | xargs -r -n1 dirname | sort -u > /etc/ld.so.conf.d/hushcast-cuda.conf",
        "ldconfig",
        "mkdir -p /tmp/cuda-stubs && ln -sf /usr/local/cuda/lib64/stubs/libcuda.so /tmp/cuda-stubs/libcuda.so.1 && "
        f"LD_LIBRARY_PATH=/tmp/cuda-stubs:${{LD_LIBRARY_PATH:-}} python3 -c {shlex.quote(_GPU_IMPORT_CHECK)} && "
        "rm -rf /tmp/cuda-stubs",
    )
)
if _LOCAL_AD_TAGGER is not None:
    _gpu_base_image = _gpu_base_image.add_local_file(
        str(_LOCAL_AD_TAGGER),
        remote_path=str(BUNDLED_AD_TAGGER),
    )

gpu_image = _gpu_base_image.add_local_python_source("hushcast_worker")


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _ensure_models() -> dict[str, Any]:
    """Downloads missing models into the Volume and verifies every file's SHA-256 (V19).

    A file that was verified before is not re-hashed (see storage.ModelVerifier), so warm
    Volumes cost only a stat per file. Returns {"changed": bool, "failed": [optional paths]}.
    """
    from hushcast_worker.storage import ModelVerifier, download_verified

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    verifier = ModelVerifier(MODELS_DIR)
    changed = False
    failed: list[str] = []

    # V19: Compare SHA-256 so updated ad_tagger.onnx reaches production Volume
    ad_dest = MODELS_DIR / AD_TAGGER_FILE.path
    if BUNDLED_AD_TAGGER.exists():
        if not ad_dest.exists() or _sha256_file(BUNDLED_AD_TAGGER) != _sha256_file(ad_dest):
            shutil.copy2(BUNDLED_AD_TAGGER, ad_dest)
            changed = True
            print(f"Updated {ad_dest} from bundled asset")

    files = list(MODEL_FILES)
    if not ad_dest.exists():
        files.append(AD_TAGGER_FILE)  # fallback: the pinned tag, checked like the rest
    for spec in files:
        dest = MODELS_DIR / spec.path
        try:
            if download_verified(spec.url, dest, sha256=spec.sha256, size=spec.size, verifier=verifier):
                changed = True
                print(f"Downloaded {spec.path} ({spec.size / (1024 * 1024):.1f} MB, sha256 {spec.sha256[:12]}...)")
        except Exception as exc:
            if spec.required:
                raise RuntimeError(f"Model {spec.path} could not be downloaded or verified: {exc}") from exc
            failed.append(spec.path)
            print(f"Optional model {spec.path} skipped: {exc}")

    # huggingface_hub resolves an unpinned "main" from refs/main when offline; point it at the pin.
    ref = MODELS_DIR / FASTER_WHISPER_CACHE / "refs" / "main"
    try:
        current = ref.read_text(encoding="utf-8") if ref.exists() else ""
    except OSError:
        current = ""
    if current != FASTER_WHISPER_REVISION:
        ref.parent.mkdir(parents=True, exist_ok=True)
        ref.write_text(FASTER_WHISPER_REVISION, encoding="utf-8")  # no newline: hf reads it raw
        changed = True
    return {"changed": changed, "failed": failed}


def _faster_whisper_snapshot_dir() -> Path:
    return MODELS_DIR / FASTER_WHISPER_SNAPSHOT


def _use_local_faster_whisper() -> bool:
    """Points faster-whisper at the pinned snapshot on the Volume and turns Hugging Face offline.

    Called once the models are on the Volume, so cold starts never call the Hub. A user-set
    HUSHCAST_FASTER_WHISPER_MODEL (another model) wins and leaves the Hub online.
    """
    snapshot = _faster_whisper_snapshot_dir()
    if not (snapshot / "model.bin").is_file():
        return False
    os.environ.setdefault("HUSHCAST_FASTER_WHISPER_MODEL", str(snapshot))
    if os.environ["HUSHCAST_FASTER_WHISPER_MODEL"] not in (str(snapshot), FASTER_WHISPER_MODEL_ID):
        return False
    os.environ.setdefault("HUSHCAST_FASTER_WHISPER_REVISION", FASTER_WHISPER_REVISION)
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
    hub_constants = sys.modules.get("huggingface_hub.constants")
    if hub_constants is not None:  # already imported: it read the env at import time
        hub_constants.HF_HUB_OFFLINE = True
    return True


def _asr_model_label() -> str:
    """The speech model the GPU worker actually runs, for /v1/health."""
    if os.environ.get("HUSHCAST_ASR_ENGINE", "faster-whisper").strip().lower() == "whispercpp":
        return Path(WHISPER_URL).name
    override = os.environ.get("HUSHCAST_FASTER_WHISPER_MODEL", "").strip()
    if override and override not in (str(_faster_whisper_snapshot_dir()), FASTER_WHISPER_MODEL_ID):
        return f"faster-whisper {Path(override).name or override}"
    return f"faster-whisper {FASTER_WHISPER_MODEL_ID}"


def _models_on_volume() -> bool:
    """Fallback for /v1/health before any GPU container has reported: are the files there?"""
    required = [MODELS_DIR / f.path for f in MODEL_FILES if f.required]
    required.append(MODELS_DIR / AD_TAGGER_FILE.path)
    return all(p.is_file() for p in required)


@app.function(
    image=gpu_image,
    volumes={str(DATA_DIR): volume},
    timeout=1800,
)
def setup_models() -> None:
    """One-time model download into the persistent Modal Volume."""
    status = _ensure_models()
    volume.commit()
    if status["failed"]:
        print(f"Optional models not available: {', '.join(status['failed'])}")
    print("All HushCast models are ready in Modal Volume 'hushcast-data'.")


@app.cls(
    image=gpu_image,
    gpu="T4",
    cpu=4.0,
    memory=16384,
    volumes={str(DATA_DIR): volume},
    secrets=[modal.Secret.from_name("hushcast-token")],
    max_containers=1,
    scaledown_window=120,
    timeout=900,
)
class GpuWorker:
    """Dedicated T4 GPU worker that warms up once per container and runs spawned jobs."""

    @modal.enter()
    def enter(self) -> None:
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        )
        status = _ensure_models()
        if status["changed"]:
            try:
                volume.commit()
            except Exception:
                logging.getLogger("hushcast_worker.modal").warning("Volume commit after model download failed", exc_info=True)
        _use_local_faster_whisper()
        from hushcast_worker.pipeline import LocalMlBackend
        from hushcast_worker.storage import WorkerCache

        max_cache = int(os.environ.get("HUSHCAST_MAX_CACHE_BYTES", str(20 * 1024 * 1024 * 1024)))
        self.cache = WorkerCache(CACHE_DIR, max_cache_bytes=max_cache)
        self.backend = LocalMlBackend(MODELS_DIR)
        self.backend.warmup()
        self._gpu_health_base = {
            "modelsReady": self.backend.models_ready(),
            "modelsLoaded": self.backend.models_loaded(),
            "whisperModel": _asr_model_label(),
            "chatModelReady": self.backend.chat_model_ready(),
        }
        self._publish_gpu_health()

    @modal.exit()
    def exit(self) -> None:
        # Tell the web tier right away that the next job pays a cold start.
        self._publish_gpu_health(warm=False)

    def _publish_gpu_health(self, *, active_job: Optional[str] = None, warm: bool = True) -> None:
        record = dict(getattr(self, "_gpu_health_base", None) or {})
        record.update(updatedAt=time.time(), warm=warm, activeJob=active_job)
        try:
            job_dict["__gpu_health__"] = record
        except Exception:
            pass

    @modal.method()
    def health_info(self) -> dict[str, Any]:
        return {
            "modelsReady": self.backend.models_ready(),
            "modelsLoaded": self.backend.models_loaded(),
            "whisperModel": _asr_model_label(),
            "chatModelReady": self.backend.chat_model_ready(),
        }

    @modal.method()
    def embed_query(self, text: str) -> list[float]:
        return self.backend.embed_query(text)

    @modal.method()
    def run_job(self, job_id: str, req_dict: dict[str, Any]) -> None:
        import asyncio
        from hushcast_worker.audio import download_audio_streaming_sha256
        from hushcast_worker.models import DesktopJobRequest, JobStage, JobState, PIPELINE_VERSION
        from hushcast_worker.server import _classify_error, _infer_stage

        log = logging.getLogger("hushcast_worker.modal")
        req = DesktopJobRequest(**req_dict)
        # Every flush replaces the whole entry, so carry over what only the web tier knows
        # (callId for DELETE /v1/jobs, its createdAt). One read, at start.
        try:
            web_entry = job_dict.get(job_id)
        except Exception:
            web_entry = None
        try:
            cancel_requested = bool(job_dict.get(f"cancel:{job_id}"))
        except Exception:
            cancel_requested = False
        # 3a: a job the web tier already gave up on (stale in the queue) or the phone cancelled
        # must not start late: the phone has moved on, and its result would be ignored.
        web_state = web_entry.get("state") if isinstance(web_entry, dict) else None
        if cancel_requested or web_state in (JobState.FAILED.value, JobState.CANCELLED.value):
            log.info("Skipping job %s: it was %s before the GPU picked it up", job_id,
                     "cancelled" if cancel_requested else web_state)
            return

        cancel_event = threading.Event()
        state_lock = threading.Lock()  # guards state_cache; never held across a Dict call
        flush_lock = threading.Lock()  # serializes Dict writes, so snapshots land in order
        now = time.time()
        state_cache: dict[str, Any] = {
            "jobId": job_id,
            "episodeId": req.episodeId,
            "state": JobState.QUEUED.value,
            "progressPercent": 0,
            "stageLabel": "Starting on T4 GPU",
            "stage": JobStage.QUEUED.value,
            "stageProgress": 0,
            "rev": 1,
            "createdAt": now,
            "heartbeatAt": now,
        }
        if isinstance(web_entry, dict):
            if web_entry.get("callId"):
                state_cache["callId"] = web_entry["callId"]
            if web_entry.get("createdAt"):
                state_cache["createdAt"] = web_entry["createdAt"]
        last_flush_mono = 0.0
        last_rev_pct = 0
        last_rev_stage = JobStage.QUEUED.value
        ml_start_mono: Optional[float] = None

        def _snapshot_locked(force: bool, bump_rev: bool) -> Optional[dict[str, Any]]:
            nonlocal last_flush_mono, last_rev_pct, last_rev_stage
            mono = time.monotonic()
            if not force and (mono - last_flush_mono) < 2.0:
                return None
            cur_pct = int(state_cache.get("progressPercent", 0))
            cur_stage = state_cache.get("stage")
            if bump_rev or cur_stage != last_rev_stage or abs(cur_pct - last_rev_pct) >= 1:
                state_cache["rev"] = int(state_cache.get("rev", 0)) + 1
                last_rev_pct = cur_pct
                last_rev_stage = cur_stage
            state_cache["heartbeatAt"] = time.time()
            last_flush_mono = mono
            return dict(state_cache)

        def _flush(force: bool = False, bump_rev: bool = False) -> None:
            with flush_lock:
                with state_lock:
                    snapshot = _snapshot_locked(force, bump_rev)
                if snapshot is not None:
                    job_dict[job_id] = snapshot

        def _update_state(force: bool = True, **updates: Any) -> None:
            with state_lock:
                state_cache.update(updates)
            _flush(force=force, bump_rev=force)

        def _on_progress(state: JobState, pct: int, label: str) -> None:
            if cancel_event.is_set():
                raise RuntimeError("Job cancelled")
            stage = _infer_stage(state, label)
            eta_val: Optional[int] = None
            if ml_start_mono is not None and 5 <= pct < 100:
                elapsed = time.monotonic() - ml_start_mono
                if elapsed >= 1.5:
                    eta_val = max(1, int(round(elapsed * (100 - pct) / pct)))
            with state_lock:
                state_cache.update(
                    state=state.value,
                    progressPercent=pct,
                    stageLabel=label,
                    stage=stage.value,
                    stageProgress=pct,
                )
                if eta_val is not None:
                    state_cache["etaSec"] = eta_val
                stage_changed = stage.value != last_rev_stage
            _flush(force=stage_changed, bump_rev=stage_changed)

        hb_stop = threading.Event()

        def _heartbeat_loop() -> None:
            # 3b/3c: the only place progress reaches the Dict during the audio download, and
            # what keeps __gpu_health__ fresh while a long job runs. It wakes at the short
            # interval and beats at the long one unless the job is downloading.
            last_beat = last_health = time.monotonic()
            while not hb_stop.wait(min(HEARTBEAT_DOWNLOAD_SEC, HEARTBEAT_SEC)):
                with state_lock:
                    downloading = state_cache.get("state") == JobState.DOWNLOADING.value
                if time.monotonic() - last_beat < (HEARTBEAT_DOWNLOAD_SEC if downloading else HEARTBEAT_SEC):
                    continue
                last_beat = time.monotonic()
                try:
                    if job_dict.get(f"cancel:{job_id}"):
                        cancel_event.set()
                        return
                except Exception:
                    pass
                try:
                    _flush(force=True)  # bumps rev only if progress moved >= 1% or the stage changed
                except Exception:
                    pass
                if time.monotonic() - last_health >= GPU_HEALTH_REFRESH_SEC:
                    last_health = time.monotonic()
                    self._publish_gpu_health(active_job=req.episodeId)

        self._publish_gpu_health(active_job=req.episodeId)
        hb_thread = threading.Thread(target=_heartbeat_loop, daemon=True, name=f"hb-{job_id[:8]}")
        hb_thread.start()

        try:
            if req.reprocessStage:
                sha = self.cache.lookup_by_episode_id(req.episodeId, req.audioUrl)
                if sha is None:
                    volume.reload()
                    self.cache.force_reload_index()
                    sha = self.cache.lookup_by_episode_id(req.episodeId, req.audioUrl)
                existing = self.cache.get_bundle(sha) if sha else None
                if existing is None:
                    raise RuntimeError("No cached transcript found for reprocessing")
                audio_path = self.cache.audio_path(existing.fileSha256)
                if not audio_path.exists():
                    audio_path = None
                existing_emb = self.cache.load_embeddings(existing.fileSha256)
                ml_start_mono = time.monotonic()
                bundle, emb_2d = self.backend.reprocess_bundle(
                    stage=req.reprocessStage,
                    existing_bundle=existing,
                    audio_path=audio_path,
                    existing_embeddings=existing_emb,
                    on_progress=_on_progress,
                    cancel_event=cancel_event,
                    podcast_title=req.podcastTitle,
                    podcast_origin=req.podcastOrigin,
                    fingerprint_store=self.cache.fingerprints,
                )
                self.cache.save_reprocessed(
                    sha256=bundle.fileSha256,
                    bundle=bundle,
                    embeddings=emb_2d,
                )
                volume.commit()
                _update_state(
                    force=True,
                    state=JobState.READY.value,
                    progressPercent=100,
                    stageLabel="Ready",
                    stage=JobStage.READY.value,
                    stageProgress=100,
                    fileSha256=bundle.fileSha256,
                    etaSec=0,
                )
                return

            _update_state(
                force=True,
                state=JobState.DOWNLOADING.value,
                progressPercent=5,
                stageLabel="Downloading episode audio",
                stage=JobStage.DOWNLOADING.value,
                stageProgress=5,
            )
            tmp_path = self.cache.root_dir / f"dl_{job_id}_{uuid.uuid4().hex[:8]}.part"

            def _dl_cb(rec: int, tot: int) -> None:
                # 3c: runs on the download's event loop, so it only touches memory. The
                # heartbeat thread writes progress to the Dict.
                if cancel_event.is_set():
                    raise RuntimeError("Job cancelled")
                if tot > 0:
                    pct = 5 + int(15 * min(1.0, rec / tot))
                    with state_lock:
                        state_cache.update(
                            state=JobState.DOWNLOADING.value,
                            progressPercent=pct,
                            stageLabel=f"Downloading audio ({rec // (1024 * 1024)} MB)",
                            stage=JobStage.DOWNLOADING.value,
                            stageProgress=pct,
                        )

            sha256, content_type = asyncio.run(
                download_audio_streaming_sha256(req.audioUrl, tmp_path, on_bytes=_dl_cb, allow_lan_feeds=False)
            )
            audio_path = self.cache.stage_audio(
                sha256=sha256,
                audio_tmp_path=tmp_path,
                content_type=content_type,
                audio_url=req.audioUrl,
                title=req.title,
                episode_id=req.episodeId,
            )
            # V5: Commit staged audio immediately so early Range pulls can succeed before ML finishes
            volume.commit()
            _update_state(force=True, fileSha256=sha256)

            existing = None if req.force else self.cache.get_bundle(sha256)
            if existing is not None and existing.pipelineVersion >= PIPELINE_VERSION:
                volume.commit()
                _update_state(
                    force=True,
                    state=JobState.READY.value,
                    progressPercent=100,
                    stageLabel="Ready (cached)",
                    stage=JobStage.READY.value,
                    stageProgress=100,
                    fileSha256=sha256,
                    etaSec=0,
                )
                return

            ml_start_mono = time.monotonic()
            bundle, emb_2d = self.backend.analyze_with_embeddings(
                audio_path=audio_path,
                file_sha256=sha256,
                episode_id=req.episodeId,
                feed_transcript_raw=req.feedTranscriptRaw,
                feed_transcript_mime=req.feedTranscriptMime,
                on_progress=_on_progress,
                language=req.language,
                feed_transcript_url=req.feedTranscriptUrl,
                feed_chapters_url=req.feedChaptersUrl,
                feed_chapters_type=req.feedChaptersType,
                cancel_event=cancel_event,
                episode_title=req.title or None,
                podcast_title=req.podcastTitle,
                podcast_origin=req.podcastOrigin,
                fingerprint_store=self.cache.fingerprints,
            )
            self.cache.save_episode(
                sha256=sha256,
                audio_tmp_path=None,
                bundle=bundle,
                audio_url=req.audioUrl,
                title=req.title or req.episodeId,
                content_type=content_type,
                embeddings=emb_2d,
            )
            volume.commit()
            _update_state(
                force=True,
                state=JobState.READY.value,
                progressPercent=100,
                stageLabel="Ready",
                stage=JobStage.READY.value,
                stageProgress=100,
                fileSha256=sha256,
                etaSec=0,
            )
        except Exception as exc:
            if cancel_event.is_set() or "cancelled" in str(exc).lower():
                _update_state(
                    force=True,
                    state=JobState.CANCELLED.value,
                    stageLabel="Cancelled",
                    stage=JobStage.CANCELLED.value,
                )
            else:
                code, retryable = _classify_error(exc)
                _update_state(
                    force=True,
                    state=JobState.FAILED.value,
                    stageLabel="Failed",
                    stage=JobStage.FAILED.value,
                    errorMessage=str(exc),
                    errorCode=code,
                    retryable=retryable,
                )
        finally:
            hb_stop.set()
            hb_thread.join(timeout=1.0)
            self._publish_gpu_health(active_job=None)


async def _dict_get_async(d: Any, key: str) -> Any:
    getter = getattr(d, "get", None)
    if getter is not None and hasattr(getter, "aio"):
        return await getter.aio(key)
    return d.get(key)


async def _dict_put_async(d: Any, key: str, value: Any) -> None:
    putter = getattr(d, "put", None)
    if putter is not None and hasattr(putter, "aio"):
        await putter.aio(key, value)
    else:
        d[key] = value


async def _call_maybe_async(fn: Any, *args: Any) -> Any:
    """Calls a Modal method via `.aio` when available so the event loop isn't blocked."""
    aio = getattr(fn, "aio", None)
    if aio is not None:
        return await aio(*args)
    return fn(*args)


@dataclass(frozen=True)
class GpuQueueStats:
    backlog: int  # inputs waiting for a GPU container
    runners: int  # live GPU containers
    running: int  # inputs running right now


async def _gpu_queue_stats() -> Optional[GpuQueueStats]:
    """Modal's live queue stats for the GPU class, or None when Modal can't say."""
    import asyncio

    try:
        stats_fn = GpuWorker().run_job.get_current_stats
    except Exception:
        return None
    try:
        stats = await asyncio.wait_for(_call_maybe_async(stats_fn), timeout=GPU_STATS_TIMEOUT_SEC)
        return GpuQueueStats(
            backlog=max(0, int(stats.backlog)),
            runners=max(0, int(stats.num_total_runners)),
            running=max(0, int(getattr(stats, "num_running_inputs", 0) or 0)),
        )
    except Exception:
        return None


def _gpu_health_view(record: dict[str, Any], stats: Optional[GpuQueueStats], now: float) -> dict[str, Any]:
    """3b: the GPU-side /v1/health fields, from the GPU's own record plus Modal's queue stats.

    `modelsReady` is None when no GPU container has reported yet (the caller then checks
    the Volume). Without queue stats, queueDepth and queuePosition are 0 and warmth comes
    from how fresh the GPU record is.
    """
    updated = float(record.get("updatedAt") or 0.0)
    fresh = bool(record) and bool(record.get("warm", True)) and (now - updated) < GPU_WARM_SEC
    warm = stats.runners > 0 if stats is not None else fresh
    return {
        "modelsReady": bool(record["modelsReady"]) if "modelsReady" in record else None,
        "modelsLoaded": bool(warm and record.get("modelsLoaded")),
        "whisperModel": str(record.get("whisperModel") or _asr_model_label()),
        "chatModelReady": bool(record.get("chatModelReady", False)),
        "activeJob": record.get("activeJob") if fresh else None,
        "queueDepth": stats.backlog if stats is not None else 0,
        # Jobs ahead of one submitted now: everything waiting plus everything running.
        "queuePosition": stats.backlog + stats.running if stats is not None else 0,
        "coldStart": not warm,
    }


@app.function(
    image=_cpu_image,
    cpu=1.0,
    memory=1024,
    volumes={str(DATA_DIR): volume},
    secrets=[modal.Secret.from_name("hushcast-token")],
    max_containers=2,
    scaledown_window=120,
    timeout=300,
)
@modal.concurrent(max_inputs=20)
@modal.asgi_app()
def fastapi_app():
    """Lightweight CPU-only FastAPI app for health, polling, search, and cached bundle/audio serving."""
    import asyncio
    from fastapi import Body, Depends, FastAPI, Header, HTTPException, Query, Request
    from fastapi.middleware.gzip import GZipMiddleware
    from fastapi.responses import FileResponse, Response
    from hushcast_worker import __version__
    from hushcast_worker.models import (
        PIPELINE_VERSION,
        DesktopHealthResponse,
        DesktopJobRequest,
        DesktopJobResponse,
        DesktopSearchResponse,
        JobStage,
        JobState,
    )
    from hushcast_worker.storage import WorkerCache, normalize_sha256

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    log = logging.getLogger("hushcast_worker.modal")
    token = (os.environ.get("HUSHCAST_WORKER_TOKEN") or "").strip()
    if not token:
        raise RuntimeError(
            "HUSHCAST_WORKER_TOKEN secret is empty or missing. "
            "Run: modal secret create hushcast-token HUSHCAST_WORKER_TOKEN=<token>"
        )

    cache = WorkerCache(CACHE_DIR)
    web = FastAPI(
        title="HushCast Cloud Worker",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    web.add_middleware(GZipMiddleware, minimum_size=1024)

    def _require_auth(authorization: Optional[str] = Header(default=None)) -> None:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="Missing bearer token")
        if authorization.removeprefix("Bearer ").strip() != token:
            raise HTTPException(status_code=401, detail="Invalid bearer token")

    @web.get("/v1/health", response_model=DesktopHealthResponse)
    async def health() -> DesktopHealthResponse:
        cache.reload_index_if_changed()
        cache_bytes, disk_free_bytes = await asyncio.to_thread(cache.cache_stats)
        cached_episodes = len(await asyncio.to_thread(cache.list_ready))
        gpu_health = await _dict_get_async(job_dict, "__gpu_health__")
        record = gpu_health if isinstance(gpu_health, dict) else {}
        view = _gpu_health_view(record, await _gpu_queue_stats(), time.time())
        if view["modelsReady"] is None:
            view["modelsReady"] = await asyncio.to_thread(_models_on_volume)
        return DesktopHealthResponse(
            version=__version__,
            apiVersion=2,
            pipelineVersion=PIPELINE_VERSION,
            hostname="modal-cloud",
            accelerator="cuda",
            deployment="modal",
            gpuName="NVIDIA T4",
            cachedEpisodes=cached_episodes,
            cacheBytes=cache_bytes,
            diskFreeBytes=disk_free_bytes,
            **view,
        )

    def _lookup_reprocess_sha(episode_id: str, audio_url: str) -> Optional[str]:
        sha = cache.lookup_by_episode_id(episode_id, audio_url)
        if sha is None:
            volume.reload()
            cache.force_reload_index()
            sha = cache.lookup_by_episode_id(episode_id, audio_url)
        return sha

    @web.post("/v1/jobs", response_model=DesktopJobResponse, response_model_exclude_none=True)
    async def submit_job(req: DesktopJobRequest = Body(...), _: None = Depends(_require_auth)) -> DesktopJobResponse:
        cache.reload_index_if_changed()
        if req.reprocessStage:
            sha = await asyncio.to_thread(_lookup_reprocess_sha, req.episodeId, req.audioUrl)
            if sha is None:
                raise HTTPException(status_code=404, detail="No cached transcript found for episode")

        job_id = uuid.uuid4().hex[:16]
        now = time.time()
        initial: dict[str, Any] = {
            "jobId": job_id,
            "episodeId": req.episodeId,
            "state": JobState.QUEUED.value,
            "progressPercent": 0,
            "stageLabel": "Queued for T4 GPU",
            "stage": JobStage.QUEUED.value,
            "stageProgress": 0,
            "rev": 1,
            "createdAt": now,
            "heartbeatAt": now,
        }
        call = await _call_maybe_async(GpuWorker().run_job.spawn, job_id, req.model_dump())
        call_id = getattr(call, "object_id", None)
        if call_id:
            initial["callId"] = call_id
            # Also under its own key: a warm GPU container can read the job entry before this
            # handler writes it, which would drop callId from the GPU's copy of the state.
            await _dict_put_async(job_dict, f"call:{job_id}", call_id)
        await _dict_put_async(job_dict, job_id, initial)
        return DesktopJobResponse(**initial)

    @web.get("/v1/jobs/{job_id}", response_model=DesktopJobResponse, response_model_exclude_none=True)
    async def get_job(
        job_id: str,
        waitMs: int = Query(default=0, ge=0, le=30000),
        sinceRev: int = Query(default=-1),
        _: None = Depends(_require_auth),
    ) -> DesktopJobResponse:
        deadline = time.monotonic() + (waitMs / 1000.0)
        while True:
            data = await _dict_get_async(job_dict, job_id)
            if data is None:
                raise HTTPException(status_code=404, detail="Job not found")
            state = data.get("state")
            if state not in ("READY", "FAILED", "CANCELLED"):
                hb_ts = float(data.get("heartbeatAt") or data.get("createdAt") or 0.0)
                stale_limit = QUEUED_STALE_SEC if state == "QUEUED" else RUNNING_STALE_SEC
                if hb_ts > 0.0 and (time.time() - hb_ts) > stale_limit:
                    data = dict(data)
                    data["state"] = JobState.FAILED.value
                    data["stage"] = JobStage.FAILED.value
                    data["stageLabel"] = "Worker timed out or crashed"
                    data["errorMessage"] = "GPU worker heartbeat timed out"
                    data["errorCode"] = "WORKER_STALE"
                    data["retryable"] = True
                    data["rev"] = int(data.get("rev", 0)) + 1
                    await _dict_put_async(job_dict, job_id, data)
                    return DesktopJobResponse(**data)
            rev = int(data.get("rev", 0))
            if waitMs <= 0 or state in ("READY", "FAILED", "CANCELLED") or rev > sinceRev or time.monotonic() >= deadline:
                return DesktopJobResponse(**data)
            await asyncio.sleep(0.35)

    @web.delete("/v1/jobs/{job_id}", response_model=DesktopJobResponse, response_model_exclude_none=True)
    async def cancel_job(job_id: str, _: None = Depends(_require_auth)) -> DesktopJobResponse:
        data = await _dict_get_async(job_dict, job_id)
        if data is None:
            raise HTTPException(status_code=404, detail="Job not found")
        await _dict_put_async(job_dict, f"cancel:{job_id}", True)
        call_id = data.get("callId") or await _dict_get_async(job_dict, f"call:{job_id}")
        if call_id and hasattr(modal, "FunctionCall"):
            try:
                fc = modal.FunctionCall.from_id(call_id)
                await _call_maybe_async(fc.cancel)
            except Exception:
                pass
        if data.get("state") not in ("READY", "FAILED", "CANCELLED"):
            data = dict(data)
            data["state"] = JobState.CANCELLED.value
            data["stage"] = JobStage.CANCELLED.value
            data["stageLabel"] = "Cancelled"
            data["rev"] = int(data.get("rev", 0)) + 1
            await _dict_put_async(job_dict, job_id, data)
        return DesktopJobResponse(**data)

    @web.get("/v1/episodes")
    def list_episodes(_: None = Depends(_require_auth)):
        volume.reload()
        cache.reload_index_if_changed()
        return cache.list_ready()

    @web.get("/v1/episodes/{key}/bundle")
    def get_bundle(key: str, _: None = Depends(_require_auth)):
        path = cache.bundle_path(key)
        if not path.exists():
            volume.reload()
            cache.reload_index_if_changed()
        if not path.exists():
            raise HTTPException(status_code=404, detail="Bundle not found")
        etag = f'"{key}-v{PIPELINE_VERSION}-{path.stat().st_mtime_ns}"'
        return FileResponse(path, media_type="application/json", headers={"ETag": etag})

    @web.get("/v1/episodes/{sha256}/audio")
    def get_audio(sha256: str, request: Request, _: None = Depends(_require_auth)):
        path = cache.audio_path(sha256)
        if not path.exists():
            volume.reload()
            cache.reload_index_if_changed()
        if not path.exists():
            raise HTTPException(status_code=404, detail="Audio not cached")
        cache.touch_audio(sha256)
        return FileResponse(path, media_type=cache.get_content_type(sha256), headers={"ETag": f'"{sha256}"'})

    @web.delete("/v1/episodes/{sha256}/audio", status_code=204, response_class=Response)
    def delete_audio(sha256: str, _: None = Depends(_require_auth)) -> Response:
        """V17: the phone verified its own copy, so drop ours from the Volume. The bundle stays.

        204 whether the file was deleted or was already gone. The index isn't rewritten here:
        the GPU container also writes index.json, and readers check the audio file itself.
        """
        clean_sha = normalize_sha256(sha256)
        if clean_sha is None:
            raise HTTPException(status_code=400, detail="Invalid sha256 parameter")
        removed = cache.delete_audio(clean_sha, update_index=False)
        if not removed:
            # This container's view of the Volume can predate the GPU's last commit.
            try:
                volume.reload()
            except Exception:
                log.warning("Volume reload before audio delete failed", exc_info=True)
            removed = cache.delete_audio(clean_sha, update_index=False)
        if removed:
            try:
                volume.commit()
            except Exception:
                log.warning("Volume commit after audio delete failed", exc_info=True)
        return Response(status_code=204)

    @web.get("/v1/search", response_model=DesktopSearchResponse)
    def search(q: str = Query(min_length=2), limit: int = Query(default=20, ge=1, le=100), _: None = Depends(_require_auth)) -> DesktopSearchResponse:
        volume.reload()
        cache.reload_index_if_changed()
        q_vec = GpuWorker().embed_query.remote(q)
        return DesktopSearchResponse(query=q, hits=cache.semantic_search(q_vec, top_k=limit))

    return web


@app.local_entrypoint()
def pair_qr(url: str, token: str = "") -> None:
    """Print a scannable hushcast://pair QR code for your deployed Modal URL."""
    from hushcast_worker.pairing import print_pairing_qr

    print("HushCast Modal Worker")
    print(f"  URL:           {url.strip()}")
    if token:
        print(f"  Pairing Token: {token}")
    print_pairing_qr(url, token)
