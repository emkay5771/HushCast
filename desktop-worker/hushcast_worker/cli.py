from __future__ import annotations

import argparse
import logging
import os
import platform
import plistlib
import secrets
import shlex
import shutil
import socket
import ssl
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Optional

import uvicorn
from zeroconf import ServiceInfo, Zeroconf

from . import __version__
from .pairing import get_lan_ips, print_pairing_qr
from .pipeline import LocalMlBackend, detect_accelerator
from .server import create_app
from .models import health_schema_problems
from .storage import ModelVerifier, WorkerCache, download_verified, sha256_file

logger = logging.getLogger("hushcast_worker.cli")

# V21: every file `setup` downloads is pinned to an immutable revision and checked by SHA-256.
# Digests come from the Hugging Face API (`/api/models/<repo>/revision/<sha>?blobs=true`,
# field lfs.sha256), or from hashing the file at that URL (MMS_FA).
_HF = "https://huggingface.co"
WHISPER_CPP_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
QWEN_EMBED_REVISION = "370f27d7550e0def9b39c1f16d3fbaa13aa67728"
QWEN_CHAT_REVISION = "f302c64a2269a69fb27b2f9473b362f5bb8e78d8"


@dataclass(frozen=True)
class ModelFile:
    filename: str
    url: str
    sha256: str
    size: int


WHISPER_BASE_EN = ModelFile(
    "ggml-base.en-q5_1.bin",
    f"{_HF}/ggerganov/whisper.cpp/resolve/{WHISPER_CPP_REVISION}/ggml-base.en-q5_1.bin",
    "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f",
    59721011,
)
WHISPER_TURBO_Q5 = ModelFile(
    "ggml-large-v3-turbo-q5_0.bin",
    f"{_HF}/ggerganov/whisper.cpp/resolve/{WHISPER_CPP_REVISION}/ggml-large-v3-turbo-q5_0.bin",
    "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
    574041195,
)
QWEN_EMBED = ModelFile(
    "Qwen3-Embedding-0.6B-Q8_0.gguf",
    f"{_HF}/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/{QWEN_EMBED_REVISION}/Qwen3-Embedding-0.6B-Q8_0.gguf",
    "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439",
    639150592,
)
QWEN_CHAT_3B = ModelFile(
    "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
    f"{_HF}/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/{QWEN_CHAT_REVISION}/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
    "9c9f56a391a3abbd5b89d0245bf6106081bcc3173119d4229235dd9d23253f94",
    1929903264,
)
# torchaudio MMS_FA weights (forced word alignment). Used whenever torch + torchaudio are
# installed, in either profile; saved where torch.hub caches them under TORCH_HOME.
MMS_FA_CHECKPOINT = ModelFile(
    "model.pt",
    "https://dl.fbaipublicfiles.com/mms/torchaudio/ctc_alignment_mling_uroman/model.pt",
    "20ef12963ab4924bef49ac4fc7f58ad5da2ee43b2c11bc8c853c9b90ecdbc680",
    1262047414,
)

PROFILES: dict[str, tuple[ModelFile, ...]] = {
    # No Silero VAD file: whisper.cpp VAD stays off on the Mac (1.0.2x) because it shifts token
    # timestamps; faster-whisper on Modal uses its own bundled VAD.
    "fast": (WHISPER_BASE_EN, QWEN_EMBED),
    "quality": (WHISPER_TURBO_Q5, QWEN_EMBED, QWEN_CHAT_3B),
}
BASE_MODELS = PROFILES["fast"]
QUALITY_MODELS = PROFILES["quality"]

LAUNCHD_LABEL = "com.hushcast.worker"


def _load_or_create_token(root_dir: Path, cli_token: Optional[str]) -> str:
    if cli_token:
        return cli_token
    env_token = os.environ.get("HUSHCAST_WORKER_TOKEN")
    if env_token:
        return env_token
    token_file = root_dir / "token"
    if token_file.exists():
        try:
            os.chmod(token_file, 0o600)
        except OSError:
            pass
        return token_file.read_text(encoding="utf-8").strip()
    token = secrets.token_urlsafe(18)
    token_file.write_text(token, encoding="utf-8")
    try:
        os.chmod(token_file, 0o600)
    except OSError:
        pass
    return token


def _sha256_file(path: Path) -> str:
    return sha256_file(path)


def _download_with_resume(
    url: str,
    dest: Path,
    expected_sha: str,
    size: Optional[int] = None,
    verifier: Optional[ModelVerifier] = None,
) -> bool:
    """Downloads (or resumes) one model and checks its SHA-256. Returns False on failure."""
    last_print = [0.0]

    def _progress(received: int, total: int) -> None:
        now = time.monotonic()
        if total > 0 and (now - last_print[0] >= 0.25 or received >= total):
            last_print[0] = now
            pct = int(100 * received / total)
            print(f"\r    {received / (1024 * 1024):.1f} / {total / (1024 * 1024):.1f} MB ({pct}%)", end="", flush=True)

    part = dest.with_name(dest.name + ".part")
    if not dest.exists():
        resume_mb = part.stat().st_size // (1024 * 1024) if part.exists() else 0
        print(f"  Downloading {dest.name}" + (f" (resuming from {resume_mb} MB)" if resume_mb else "") + " ...")
    try:
        downloaded = download_verified(url, dest, sha256=expected_sha, size=size, verifier=verifier, on_progress=_progress)
    except Exception as exc:
        print()
        print(f"  [FAIL] {dest.name}: {exc}")
        return False
    if downloaded:
        print()
        print(f"  [OK] Saved {dest.name} (sha256={expected_sha[:12]}...)")
    else:
        print(f"  [OK] {dest.name} already present (sha256={expected_sha[:12]}...)")
    return True


def _torch_home(models_dir: Path) -> Path:
    # Same default as ForcedAligner(models_dir / "torch") in pipeline.py.
    return Path(os.environ.get("TORCH_HOME") or (models_dir / "torch")).expanduser()


def _prefetch_mms_fa(models_dir: Path, verifier: ModelVerifier) -> bool:
    """Downloads the MMS_FA weights when torch + torchaudio are installed (V21)."""
    try:
        import torch  # noqa: F401
        import torchaudio  # noqa: F401
    except Exception:
        print("  [INFO] torch/torchaudio not installed: word timings use whisper.cpp DTW.")
        print("         For MMS_FA word alignment: pip install torch torchaudio, then rerun setup (~1.3 GB).")
        return True
    dest = _torch_home(models_dir) / "hub" / "checkpoints" / MMS_FA_CHECKPOINT.filename
    return _download_with_resume(
        MMS_FA_CHECKPOINT.url, dest, MMS_FA_CHECKPOINT.sha256, MMS_FA_CHECKPOINT.size, verifier
    )


def _run_smoke_test(backend: LocalMlBackend) -> bool:
    """Runs a quick 10s silent-WAV inference smoke test (V21)."""
    import wave
    import numpy as np

    try:
        with tempfile.TemporaryDirectory(prefix="hushcast_smoke_") as td:
            wav_path = Path(td) / "smoke10s.wav"
            samples = np.zeros(16000 * 10, dtype=np.int16)
            with wave.open(str(wav_path), "wb") as wf:
                wf.setnchannels(1)
                wf.setsampwidth(2)
                wf.setframerate(16000)
                wf.writeframes(samples.tobytes())
            bundle = backend.analyze(
                audio_path=wav_path,
                file_sha256="smoke",
                episode_id="smoke",
                feed_transcript_raw=None,
                feed_transcript_mime="",
                on_progress=lambda *_args: None,
            )
            print(f"  [OK] Smoke test passed ({bundle.analysisDurationMs} ms, model={bundle.whisperModel})")
            return True
    except Exception as exc:
        print(f"  [WARN] Smoke test skipped/failed: {exc}")
        return False


def _service_program_args(data_dir: Path, port: int) -> list[str]:
    exe = shutil.which("hushcast-worker")
    base = [exe] if exe else [sys.executable, "-m", "hushcast_worker.cli"]
    return base + ["--port", str(port), "--data-dir", str(data_dir)]


def build_launchd_plist(program_args: list[str], data_dir: Path, *, env: Optional[dict[str, str]] = None) -> str:
    """A LaunchAgent that starts the worker at login and restarts it if it stops (V21)."""
    log_dir = data_dir / "logs"
    environment = {
        # launchd starts with a bare PATH; ffmpeg/ffprobe usually live in Homebrew's bin.
        "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        "PYTHONUNBUFFERED": "1",
    }
    environment.update(env or {})
    plist: dict[str, Any] = {
        "Label": LAUNCHD_LABEL,
        "ProgramArguments": list(program_args),
        "WorkingDirectory": str(data_dir),
        "EnvironmentVariables": environment,
        "RunAtLoad": True,
        "KeepAlive": True,
        "ThrottleInterval": 10,
        "ProcessType": "Interactive",  # don't let macOS throttle long transcriptions
        "StandardOutPath": str(log_dir / "worker.log"),
        "StandardErrorPath": str(log_dir / "worker.err.log"),
    }
    return plistlib.dumps(plist, sort_keys=False).decode("utf-8")


def build_systemd_unit(program_args: list[str], data_dir: Path) -> str:
    exec_start = " ".join(shlex.quote(a) for a in program_args)
    return (
        "[Unit]\n"
        "Description=HushCast desktop worker\n"
        "After=network-online.target\n"
        "Wants=network-online.target\n\n"
        "[Service]\n"
        f"ExecStart={exec_start}\n"
        f"WorkingDirectory={data_dir}\n"
        "Environment=PYTHONUNBUFFERED=1\n"
        "Restart=always\n"
        "RestartSec=10\n\n"
        "[Install]\n"
        "WantedBy=default.target\n"
    )


def _print_service_snippet(data_dir: Path, port: int) -> None:
    args = _service_program_args(data_dir, port)
    if platform.system() == "Darwin":
        plist_text = build_launchd_plist(args, data_dir)
        plist_copy = data_dir / f"{LAUNCHD_LABEL}.plist"
        try:
            (data_dir / "logs").mkdir(parents=True, exist_ok=True)
            plist_copy.write_text(plist_text, encoding="utf-8")
        except OSError:
            plist_copy = None
        print("\nRun the worker at login (macOS launchd, restarts it if it stops):")
        if plist_copy is not None:
            print(f"  A ready-to-use plist was written to {plist_copy}. Install it with:")
            print(f"    cp {plist_copy} ~/Library/LaunchAgents/{LAUNCHD_LABEL}.plist")
        else:
            print(f"  Save this as ~/Library/LaunchAgents/{LAUNCHD_LABEL}.plist:\n")
            print(plist_text)
        print(f"    launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/{LAUNCHD_LABEL}.plist")
        print(f"  Logs: {data_dir / 'logs'}/worker.log and worker.err.log")
        print(f"  Stop: launchctl bootout gui/$(id -u)/{LAUNCHD_LABEL}")
        print("  For HTTPS, add --tls-cert/--tls-key (and their paths) to ProgramArguments.")
    else:
        print("\nOptional systemd user service (~/.config/systemd/user/hushcast-worker.service):\n")
        print(build_systemd_unit(args, data_dir))
        print("  Enable: systemctl --user daemon-reload && systemctl --user enable --now hushcast-worker")
        print("  Logs:   journalctl --user -u hushcast-worker -f")


def _run_setup(data_dir: Path, ad_tagger_onnx: Optional[Path], profile: str, skip_smoke: bool) -> int:
    models_dir = data_dir / "models"
    models_dir.mkdir(parents=True, exist_ok=True)
    print(f"HushCast Worker Setup v{__version__} (profile={profile})")
    print(f"Models directory: {models_dir}")

    for tool in ("ffmpeg", "ffprobe"):
        path = shutil.which(tool)
        print(f"  {tool:<10}: {path or 'MISSING (install ffmpeg)'}")

    verifier = ModelVerifier(models_dir)
    for model in PROFILES[profile]:
        if not _download_with_resume(model.url, models_dir / model.filename, model.sha256, model.size, verifier):
            return 1
    if not _prefetch_mms_fa(models_dir, verifier):
        return 1

    repo_onnx = Path(__file__).resolve().parents[2] / "model" / "ad_tagger.onnx"
    onnx_src = ad_tagger_onnx or (repo_onnx if repo_onnx.exists() else None)
    onnx_dest = models_dir / "ad_tagger.onnx"
    if onnx_src and onnx_src.exists():
        if not onnx_dest.exists() or _sha256_file(onnx_src) != _sha256_file(onnx_dest):
            shutil.copy2(onnx_src, onnx_dest)
            print(f"  [OK] Copied {onnx_dest.name}")

    backend = LocalMlBackend(models_dir, ad_tagger_onnx=onnx_dest if onnx_dest.exists() else onnx_src)
    print("\nSelf-test:")
    print(f"  ML imports ready : {backend.ml_imports_ready()}")
    print(f"  Models ready     : {backend.models_ready()}")
    print(f"  Accelerator      : {detect_accelerator()}")
    if backend.models_ready() and not skip_smoke:
        _run_smoke_test(backend)
    _print_service_snippet(data_dir, 8765)
    return 0 if backend.models_ready() else 1


def cert_sha256_fingerprint(cert_path: Path) -> str:
    """SHA-256 of the leaf certificate (DER), as the phone pins it: colon-separated hex."""
    pem = Path(cert_path).read_text(encoding="utf-8")
    begin = pem.index("-----BEGIN CERTIFICATE-----")
    end = pem.index("-----END CERTIFICATE-----", begin) + len("-----END CERTIFICATE-----")
    der = ssl.PEM_cert_to_DER_cert(pem[begin:end])
    import hashlib

    digest = hashlib.sha256(der).hexdigest().upper()
    return ":".join(digest[i : i + 2] for i in range(0, len(digest), 2))


def tls_options(cert: Optional[Path], key: Optional[Path]) -> dict[str, str]:
    """V24: uvicorn ssl_* arguments for optional HTTPS. Raises ValueError on bad input."""
    if cert is None and key is None:
        return {}
    if cert is None or key is None:
        raise ValueError("--tls-cert and --tls-key must be given together")
    for label, path in (("--tls-cert", cert), ("--tls-key", key)):
        if not Path(path).expanduser().is_file():
            raise ValueError(f"{label} file not found: {path}")
    cert_path = str(Path(cert).expanduser())
    try:
        ssl.create_default_context(ssl.Purpose.CLIENT_AUTH).load_cert_chain(cert_path, str(Path(key).expanduser()))
    except (ssl.SSLError, OSError) as exc:
        raise ValueError(f"TLS certificate/key could not be loaded: {exc}") from exc
    return {"ssl_certfile": cert_path, "ssl_keyfile": str(Path(key).expanduser())}


def _run_pair(data_dir: Path, port: int, cli_token: Optional[str], tls_cert: Optional[Path] = None) -> int:
    data_dir.mkdir(parents=True, exist_ok=True)
    token = _load_or_create_token(data_dir, cli_token)
    lan_ips = get_lan_ips()
    if not lan_ips:
        print("Could not detect a non-loopback LAN IPv4 address.")
        return 1
    scheme = "https" if tls_cert else "http"
    primary_url = f"{scheme}://{lan_ips[0]}:{port}"
    print(f"HushCast Worker Pairing (v{__version__})")
    for ip in lan_ips:
        print(f"  Candidate URL: {scheme}://{ip}:{port}")
    print(f"  Pairing Token: {token}")
    if tls_cert:
        print(f"  TLS SHA-256:   {cert_sha256_fingerprint(tls_cert)}")
    print_pairing_qr(primary_url, token)
    return 0


def check_health(
    url: str,
    *,
    attempts: int = 6,
    delay_sec: float = 10.0,
    timeout_sec: float = 60.0,
    expect_deployment: Optional[str] = None,
    client: Any = None,
    sleep: Callable[[float], None] = time.sleep,
) -> list[str]:
    """V19 smoke test: GET <url>/v1/health and check it against the schema.

    Retries connection errors and 5xx (a fresh deploy cold-starts). Returns a list of
    problems; empty means healthy.
    """
    import httpx

    health_url = url.strip().rstrip("/") + "/v1/health"
    own_client = client is None
    http = client or httpx.Client(timeout=timeout_sec, follow_redirects=True)
    last_problem = "no attempt made"
    try:
        for attempt in range(1, max(1, attempts) + 1):
            try:
                resp = http.get(health_url)
            except httpx.HTTPError as exc:
                last_problem = f"request failed: {type(exc).__name__}: {exc}"
            else:
                if resp.status_code == 200:
                    try:
                        payload = resp.json()
                    except ValueError:
                        return [f"response is not JSON: {resp.text[:200]!r}"]
                    return health_schema_problems(payload, expect_deployment=expect_deployment)
                last_problem = f"HTTP {resp.status_code}: {resp.text[:200]}"
                if resp.status_code < 500:
                    return [last_problem]
            if attempt < attempts:
                sleep(delay_sec)
        return [last_problem]
    finally:
        if own_client:
            http.close()


def _run_health_check(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="hushcast-worker health-check",
        description="Check a deployed worker's /v1/health (status 200 and the current schema).",
    )
    parser.add_argument("--url", required=True, help="Worker base URL, e.g. https://<you>--hushcast-worker-fastapi-app.modal.run")
    parser.add_argument("--attempts", type=int, default=6)
    parser.add_argument("--delay", type=float, default=10.0, help="Seconds between attempts")
    parser.add_argument("--timeout", type=float, default=60.0, help="Per-request timeout in seconds")
    parser.add_argument("--expect-deployment", default=None, help='Require this "deployment" value (e.g. modal)')
    args = parser.parse_args(argv)
    problems = check_health(
        args.url,
        attempts=args.attempts,
        delay_sec=args.delay,
        timeout_sec=args.timeout,
        expect_deployment=args.expect_deployment,
    )
    if problems:
        print(f"Health check FAILED for {args.url}:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"Health check passed for {args.url}")
    return 0


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    if len(sys.argv) > 1 and sys.argv[1] == "setup":
        setup_parser = argparse.ArgumentParser(prog="hushcast-worker setup")
        setup_parser.add_argument("--data-dir", type=Path, default=Path.home() / ".hushcast-worker")
        setup_parser.add_argument("--ad-tagger-onnx", type=Path, default=None)
        setup_parser.add_argument(
            "--profile",
            choices=("fast", "quality"),
            default="fast",
            help="fast = base.en (fast CPU/Apple Silicon); quality = large-v3-turbo-q5_0 + Qwen2.5-3B",
        )
        setup_parser.add_argument("--skip-smoke-test", action="store_true")
        s_args = setup_parser.parse_args(sys.argv[2:])
        raise SystemExit(
            _run_setup(s_args.data_dir, s_args.ad_tagger_onnx, s_args.profile, s_args.skip_smoke_test)
        )

    if len(sys.argv) > 1 and sys.argv[1] == "pair":
        pair_parser = argparse.ArgumentParser(prog="hushcast-worker pair")
        pair_parser.add_argument("--data-dir", type=Path, default=Path.home() / ".hushcast-worker")
        pair_parser.add_argument("--port", type=int, default=8765)
        pair_parser.add_argument("--token", default=None)
        pair_parser.add_argument("--tls-cert", type=Path, default=None, help="Show an https URL and the cert fingerprint")
        p_args = pair_parser.parse_args(sys.argv[2:])
        raise SystemExit(_run_pair(p_args.data_dir, p_args.port, p_args.token, p_args.tls_cert))

    if len(sys.argv) > 1 and sys.argv[1] == "health-check":
        raise SystemExit(_run_health_check(sys.argv[2:]))

    parser = argparse.ArgumentParser(description="HushCast Desktop Companion Worker")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=Path.home() / ".hushcast-worker",
        help="Directory for cached episodes, models, and pairing token",
    )
    parser.add_argument(
        "--ad-tagger-onnx",
        type=Path,
        default=None,
        help="Optional path to ad_tagger.onnx (defaults to <data-dir>/models/ad_tagger.onnx or repo asset)",
    )
    parser.add_argument("--token", default=None, help="Override pairing token")
    parser.add_argument(
        "--insecure-no-auth",
        action="store_true",
        help="Allow running without a pairing token (NOT recommended outside isolated tests)",
    )
    parser.add_argument(
        "--allow-lan-feeds",
        action="store_true",
        help="Allow downloading episode audio from private LAN IP ranges (e.g. local Audiobookshelf)",
    )
    parser.add_argument("--no-mdns", action="store_true", help="Disable Bonjour/mDNS broadcast")
    parser.add_argument(
        "--max-cache-gb",
        type=float,
        default=10.0,
        help="Maximum disk space for cached .audio files before LRU eviction (0 = unlimited)",
    )
    parser.add_argument(
        "--clear-cache",
        action="store_true",
        help="Delete all cached episodes, bundles, and embeddings in <data-dir>/cache and exit",
    )
    parser.add_argument(
        "--clear-audio-cache",
        action="store_true",
        help="Delete only cached .audio files in <data-dir>/cache (keeps transcripts/bundles) and exit",
    )
    parser.add_argument("--tls-cert", type=Path, default=None, help="PEM certificate: serve HTTPS (with --tls-key)")
    parser.add_argument("--tls-key", type=Path, default=None, help="PEM private key for --tls-cert")
    args = parser.parse_args()
    try:
        ssl_kwargs = tls_options(args.tls_cert, args.tls_key)
    except ValueError as exc:
        parser.error(str(exc))

    args.data_dir.mkdir(parents=True, exist_ok=True)
    max_cache_bytes = int(max(0.0, args.max_cache_gb) * (1024**3))
    cache = WorkerCache(args.data_dir / "cache", max_cache_bytes=max_cache_bytes)

    if args.clear_cache or args.clear_audio_cache:
        audio_only = bool(args.clear_audio_cache and not args.clear_cache)
        stats = cache.clear(audio_only=audio_only)
        freed_mb = stats["freedBytes"] / (1024 * 1024)
        mode = "audio-only" if audio_only else "full"
        print(
            f"Cleared {mode} cache in {cache.root_dir}: "
            f"{stats['removedFiles']} file(s) removed, {freed_mb:.2f} MB freed."
        )
        return

    token: Optional[str] = None
    if not args.insecure_no_auth:
        token = _load_or_create_token(args.data_dir, args.token)

    repo_onnx = (
        Path(__file__).resolve().parents[2]
        / "core"
        / "automute-engine"
        / "src"
        / "main"
        / "assets"
        / "ad_tagger.onnx"
    )
    onnx_path = args.ad_tagger_onnx or (repo_onnx if repo_onnx.exists() else None)

    backend = LocalMlBackend(args.data_dir / "models", ad_tagger_onnx=onnx_path)
    app = create_app(
        cache=cache,
        backend=backend,
        token=token,
        allow_insecure_no_auth=args.insecure_no_auth,
        allow_lan_feeds=args.allow_lan_feeds,
    )

    lan_ips = get_lan_ips()
    lan_ip = lan_ips[0] if lan_ips else None
    display_host = lan_ip or "127.0.0.1"
    scheme = "https" if ssl_kwargs else "http"
    worker_url = f"{scheme}://{display_host}:{args.port}"
    print(f"HushCast Desktop Worker v{__version__} ({detect_accelerator().upper()})")
    print(f"  URL:           {worker_url}")
    for extra_ip in lan_ips[1:]:
        print(f"  Alt URL:       {scheme}://{extra_ip}:{args.port}")
    if token:
        print(f"  Pairing Token: {token}")
    else:
        print("  Pairing Token: DISABLED (--insecure-no-auth)")
    if ssl_kwargs:
        print(f"  TLS SHA-256:   {cert_sha256_fingerprint(Path(ssl_kwargs['ssl_certfile']))}")
        print("                 (enter this as the certificate fingerprint on the phone to pin it)")
    print(f"  Models Ready:  {backend.models_ready()} (dir: {backend.models_dir})")
    print(f"  Whisper Model: {backend.whisper_model_name()}")
    print(f"  Chat Model:    {backend.chat_model_path().name if backend.chat_model_ready() else 'Extractive fallback (drop any Instruct GGUF in models dir to enable LLM summaries)'}")
    if lan_ip is None:
        print("  WARNING: Could not detect a non-loopback LAN IP; skipping pairing QR code.")
    elif token:
        print_pairing_qr(worker_url, token)

    zc: Optional[Zeroconf] = None
    info: Optional[ServiceInfo] = None
    if not args.no_mdns and lan_ips:
        try:
            hostname = socket.gethostname().split(".")[0]
            info = ServiceInfo(
                "_hushcast._tcp.local.",
                f"HushCast-{hostname}._hushcast._tcp.local.",
                addresses=[socket.inet_aton(ip) for ip in lan_ips],
                port=args.port,
                properties={
                    "version": __version__,
                    "accel": detect_accelerator(),
                    "scheme": scheme,
                },
            )
            zc = Zeroconf()
            zc.register_service(info)
            print(f"  mDNS:          Advertising _hushcast._tcp.local. ({len(lan_ips)} addr)")
        except Exception as exc:
            print(f"  mDNS warning:  {exc}")

    try:
        uvicorn.run(app, host=args.host, port=args.port, log_level="warning", access_log=False, **ssl_kwargs)
    finally:
        if zc and info:
            zc.unregister_service(info)
            zc.close()


if __name__ == "__main__":
    main()
