from __future__ import annotations

import argparse
import hashlib
import logging
import os
import platform
import secrets
import shutil
import socket
import sys
import tempfile
from pathlib import Path
from typing import Optional

import uvicorn
from zeroconf import ServiceInfo, Zeroconf

from . import __version__
from .pairing import get_lan_ips, print_pairing_qr
from .pipeline import LocalMlBackend, detect_accelerator
from .server import create_app
from .storage import WorkerCache

logger = logging.getLogger("hushcast_worker.cli")

# Pinned model URLs and optional SHA-256 digests (override via env if using custom mirrors)
BASE_MODELS = [
    (
        "ggml-base.en-q5_1.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en-q5_1.bin",
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
    ),
    (
        "Qwen3-Embedding-0.6B-Q8_0.gguf",
        "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/c80565f1b529c2b2f58f48c0a6e1b2f30f22c2a3/Qwen3-Embedding-0.6B-Q8_0.gguf",
        None,
    ),
]

QUALITY_MODELS = [
    (
        "ggml-large-v3-turbo-q5_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        None,
    ),
    (
        "Qwen3-Embedding-0.6B-Q8_0.gguf",
        "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/c80565f1b529c2b2f58f48c0a6e1b2f30f22c2a3/Qwen3-Embedding-0.6B-Q8_0.gguf",
        None,
    ),
    (
        "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
        None,
    ),
]


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
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _download_with_resume(url: str, dest: Path, expected_sha: Optional[str]) -> bool:
    import httpx

    if dest.exists() and dest.stat().st_size > 0:
        if expected_sha:
            actual = _sha256_file(dest)
            if actual == expected_sha:
                print(f"  [OK] {dest.name} already present (sha256={actual[:12]}...)")
                return True
            print(f"  [WARN] {dest.name} checksum mismatch; re-downloading")
            dest.unlink(missing_ok=True)
        else:
            print(f"  [OK] {dest.name} already present ({dest.stat().st_size / (1024 * 1024):.1f} MB)")
            return True

    part = dest.with_suffix(".part")
    resume_pos = part.stat().st_size if part.exists() else 0
    headers = {"Range": f"bytes={resume_pos}-"} if resume_pos > 0 else {}

    print(f"  Downloading {dest.name}" + (f" (resuming from {resume_pos // (1024 * 1024)} MB)" if resume_pos else "") + " ...")
    with httpx.stream("GET", url, headers=headers, follow_redirects=True, timeout=600.0) as resp:
        if resp.status_code == 416:
            resume_pos = 0
            part.unlink(missing_ok=True)
            with httpx.stream("GET", url, follow_redirects=True, timeout=600.0) as resp2:
                resp2.raise_for_status()
                total = int(resp2.headers.get("content-length") or 0)
                received = 0
                with part.open("wb") as f:
                    for chunk in resp2.iter_bytes(chunk_size=1024 * 1024):
                        f.write(chunk)
                        received += len(chunk)
                        if total > 0:
                            pct = int(100 * received / total)
                            print(f"\r    {received / (1024 * 1024):.1f} / {total / (1024 * 1024):.1f} MB ({pct}%)", end="", flush=True)
        else:
            resp.raise_for_status()
            is_resume = resp.status_code == 206 and resume_pos > 0
            total = int(resp.headers.get("content-length") or 0) + (resume_pos if is_resume else 0)
            received = resume_pos if is_resume else 0
            mode = "ab" if is_resume else "wb"
            with part.open(mode) as f:
                for chunk in resp.iter_bytes(chunk_size=1024 * 1024):
                    f.write(chunk)
                    received += len(chunk)
                    if total > 0:
                        pct = int(100 * received / total)
                        print(f"\r    {received / (1024 * 1024):.1f} / {total / (1024 * 1024):.1f} MB ({pct}%)", end="", flush=True)
    print()

    actual_sha = _sha256_file(part)
    if expected_sha and actual_sha != expected_sha:
        part.unlink(missing_ok=True)
        print(f"  [FAIL] Checksum mismatch for {dest.name}: expected {expected_sha}, got {actual_sha}")
        return False
    os.replace(part, dest)
    print(f"  [OK] Saved {dest.name} (sha256={actual_sha[:12]}...)")
    return True


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


def _print_service_snippet(data_dir: Path, port: int) -> None:
    exe = shutil.which("hushcast-worker") or f"{sys.executable} -m hushcast_worker.cli"
    if platform.system() == "Darwin":
        print("\nOptional macOS launchd plist (~/Library/LaunchAgents/com.hushcast.worker.plist):")
        print(
            f"  <string>{exe}</string> with args: --port {port} --data-dir {data_dir}"
        )
    else:
        print("\nOptional systemd user service (~/.config/systemd/user/hushcast-worker.service):")
        print(f"  ExecStart={exe} --port {port} --data-dir {data_dir}")


def _run_setup(data_dir: Path, ad_tagger_onnx: Optional[Path], profile: str, skip_smoke: bool) -> int:
    models_dir = data_dir / "models"
    models_dir.mkdir(parents=True, exist_ok=True)
    print(f"HushCast Worker Setup v{__version__} (profile={profile})")
    print(f"Models directory: {models_dir}")

    for tool in ("ffmpeg", "ffprobe"):
        path = shutil.which(tool)
        print(f"  {tool:<10}: {path or 'MISSING (install ffmpeg)'}")

    models = QUALITY_MODELS if profile == "quality" else BASE_MODELS
    for filename, url, expected_sha in models:
        if not _download_with_resume(url, models_dir / filename, expected_sha):
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


def _run_pair(data_dir: Path, port: int, cli_token: Optional[str]) -> int:
    data_dir.mkdir(parents=True, exist_ok=True)
    token = _load_or_create_token(data_dir, cli_token)
    lan_ips = get_lan_ips()
    if not lan_ips:
        print("Could not detect a non-loopback LAN IPv4 address.")
        return 1
    primary_url = f"http://{lan_ips[0]}:{port}"
    print(f"HushCast Worker Pairing (v{__version__})")
    for ip in lan_ips:
        print(f"  Candidate URL: http://{ip}:{port}")
    print(f"  Pairing Token: {token}")
    print_pairing_qr(primary_url, token)
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
        p_args = pair_parser.parse_args(sys.argv[2:])
        raise SystemExit(_run_pair(p_args.data_dir, p_args.port, p_args.token))

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
    args = parser.parse_args()

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
    worker_url = f"http://{display_host}:{args.port}"
    print(f"HushCast Desktop Worker v{__version__} ({detect_accelerator().upper()})")
    print(f"  URL:           {worker_url}")
    for extra_ip in lan_ips[1:]:
        print(f"  Alt URL:       http://{extra_ip}:{args.port}")
    if token:
        print(f"  Pairing Token: {token}")
    else:
        print("  Pairing Token: DISABLED (--insecure-no-auth)")
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
                properties={"version": __version__, "accel": detect_accelerator()},
            )
            zc = Zeroconf()
            zc.register_service(info)
            print(f"  mDNS:          Advertising _hushcast._tcp.local. ({len(lan_ips)} addr)")
        except Exception as exc:
            print(f"  mDNS warning:  {exc}")

    try:
        uvicorn.run(app, host=args.host, port=args.port, log_level="warning", access_log=False)
    finally:
        if zc and info:
            zc.unregister_service(info)
            zc.close()


if __name__ == "__main__":
    main()
