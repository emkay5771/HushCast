from __future__ import annotations

import argparse
import os
import secrets
import socket
from pathlib import Path
from typing import Optional

import uvicorn
from zeroconf import ServiceInfo, Zeroconf

from .pipeline import LocalMlBackend, detect_accelerator
from .server import create_app
from .storage import WorkerCache


def _get_lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


def _load_or_create_token(root_dir: Path, cli_token: Optional[str]) -> str:
    if cli_token:
        return cli_token
    env_token = os.environ.get("HUSHCAST_WORKER_TOKEN")
    if env_token:
        return env_token
    token_file = root_dir / "token"
    if token_file.exists():
        return token_file.read_text(encoding="utf-8").strip()
    token = secrets.token_urlsafe(18)
    token_file.write_text(token, encoding="utf-8")
    return token


def build_pairing_uri(url: str, token: str) -> str:
    from urllib.parse import quote

    clean_url = url.strip().rstrip("/")
    return f"hushcast://pair?url={quote(clean_url, safe='')}&token={quote(token, safe='')}"


def print_pairing_qr(url: str, token: str) -> None:
    uri = build_pairing_uri(url, token)
    print(f"  Pair URI:      {uri}")
    try:
        import qrcode

        qr = qrcode.QRCode(border=2)
        qr.add_data(uri)
        qr.make(fit=True)
        print("\n  Scan with your phone's Camera or HushCast Settings → Scan QR:")
        qr.print_ascii(invert=True)
    except Exception:
        pass


def main() -> None:
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
    app = create_app(cache=cache, backend=backend, token=token)

    lan_ip = _get_lan_ip()
    worker_url = f"http://{lan_ip}:{args.port}"
    print(f"HushCast Desktop Worker v0.1.0 ({detect_accelerator().upper()})")
    print(f"  URL:           {worker_url}")
    print(f"  Pairing Token: {token}")
    print(f"  Models Ready:  {backend.models_ready()} (dir: {backend.models_dir})")
    print(f"  Whisper Model: {backend.whisper_model_name()}")
    print(f"  Chat Model:    {backend.chat_model_path().name if backend.chat_model_ready() else 'Extractive fallback (drop any Instruct GGUF in models dir to enable LLM summaries)'}")
    print_pairing_qr(worker_url, token)

    zc: Optional[Zeroconf] = None
    info: Optional[ServiceInfo] = None
    if not args.no_mdns:
        try:
            hostname = socket.gethostname().split(".")[0]
            info = ServiceInfo(
                "_hushcast._tcp.local.",
                f"HushCast-{hostname}._hushcast._tcp.local.",
                addresses=[socket.inet_aton(lan_ip)],
                port=args.port,
                properties={"version": "0.1.0", "accel": detect_accelerator()},
            )
            zc = Zeroconf()
            zc.register_service(info)
            print("  mDNS:          Advertising _hushcast._tcp.local.")
        except Exception as exc:
            print(f"  mDNS warning:  {exc}")

    try:
        uvicorn.run(app, host=args.host, port=args.port, log_level="info")
    finally:
        if zc and info:
            zc.unregister_service(info)
            zc.close()


if __name__ == "__main__":
    main()
