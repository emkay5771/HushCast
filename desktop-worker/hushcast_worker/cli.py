from __future__ import annotations

import argparse
import hashlib
import logging
import os
import secrets
import shutil
import socket
import sys
from pathlib import Path
from typing import Optional

import uvicorn
from zeroconf import ServiceInfo, Zeroconf

from . import __version__
from .pairing import get_lan_ip, print_pairing_qr
from .pipeline import LocalMlBackend, detect_accelerator
from .server import create_app
from .storage import WorkerCache

logger = logging.getLogger("hushcast_worker.cli")

# Pinned model URLs and optional SHA-256 digests (override via env if using custom mirrors)
DEFAULT_MODELS = [
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


def _run_setup(data_dir: Path, ad_tagger_onnx: Optional[Path]) -> int:
    import httpx

    models_dir = data_dir / "models"
    models_dir.mkdir(parents=True, exist_ok=True)
    print(f"HushCast Worker Setup v{__version__}")
    print(f"Models directory: {models_dir}")

    # Check ffmpeg / ffprobe
    for tool in ("ffmpeg", "ffprobe"):
        path = shutil.which(tool)
        print(f"  {tool:<10}: {path or 'MISSING (install ffmpeg)'}")

    for filename, url, expected_sha in DEFAULT_MODELS:
        dest = models_dir / filename
        if dest.exists() and dest.stat().st_size > 0:
            print(f"  [OK] {filename} already present ({dest.stat().st_size / (1024 * 1024):.1f} MB)")
            continue
        tmp = dest.with_suffix(".part")
        print(f"  Downloading {filename} ...")
        hasher = hashlib.sha256()
        with httpx.stream("GET", url, follow_redirects=True, timeout=600.0) as resp:
            resp.raise_for_status()
            with tmp.open("wb") as f:
                for chunk in resp.iter_bytes(chunk_size=1024 * 1024):
                    f.write(chunk)
                    hasher.update(chunk)
        actual_sha = hasher.hexdigest()
        if expected_sha and actual_sha != expected_sha:
            tmp.unlink(missing_ok=True)
            print(f"  [FAIL] Checksum mismatch for {filename}: expected {expected_sha}, got {actual_sha}")
            return 1
        os.replace(tmp, dest)
        print(f"  [OK] Saved {filename} (sha256={actual_sha[:12]}...)")

    repo_onnx = Path(__file__).resolve().parents[2] / "model" / "ad_tagger.onnx"
    onnx_src = ad_tagger_onnx or (repo_onnx if repo_onnx.exists() else None)
    onnx_dest = models_dir / "ad_tagger.onnx"
    if onnx_src and onnx_src.exists() and not onnx_dest.exists():
        shutil.copy2(onnx_src, onnx_dest)
        print(f"  [OK] Copied {onnx_dest.name}")

    backend = LocalMlBackend(models_dir, ad_tagger_onnx=onnx_dest if onnx_dest.exists() else onnx_src)
    print("\nSelf-test:")
    print(f"  ML imports ready : {backend.ml_imports_ready()}")
    print(f"  Models ready     : {backend.models_ready()}")
    print(f"  Accelerator      : {detect_accelerator()}")
    return 0 if backend.models_ready() else 1


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    # Support `hushcast-worker setup ...` without breaking existing flags
    if len(sys.argv) > 1 and sys.argv[1] == "setup":
        setup_parser = argparse.ArgumentParser(prog="hushcast-worker setup")
        setup_parser.add_argument("--data-dir", type=Path, default=Path.home() / ".hushcast-worker")
        setup_parser.add_argument("--ad-tagger-onnx", type=Path, default=None)
        s_args = setup_parser.parse_args(sys.argv[2:])
        raise SystemExit(_run_setup(s_args.data_dir, s_args.ad_tagger_onnx))

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
    cache = WorkerCache(args.data_dir / "cache")

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

    lan_ip = get_lan_ip()
    display_host = lan_ip or "127.0.0.1"
    worker_url = f"http://{display_host}:{args.port}"
    print(f"HushCast Desktop Worker v{__version__} ({detect_accelerator().upper()})")
    print(f"  URL:           {worker_url}")
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
    if not args.no_mdns and lan_ip is not None:
        try:
            hostname = socket.gethostname().split(".")[0]
            info = ServiceInfo(
                "_hushcast._tcp.local.",
                f"HushCast-{hostname}._hushcast._tcp.local.",
                addresses=[socket.inet_aton(lan_ip)],
                port=args.port,
                properties={"version": __version__, "accel": detect_accelerator()},
            )
            zc = Zeroconf()
            zc.register_service(info)
            print("  mDNS:          Advertising _hushcast._tcp.local.")
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
