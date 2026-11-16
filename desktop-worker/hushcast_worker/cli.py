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
    args = parser.parse_args()

    args.data_dir.mkdir(parents=True, exist_ok=True)
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

    cache = WorkerCache(args.data_dir / "cache")
    backend = LocalMlBackend(args.data_dir / "models", ad_tagger_onnx=onnx_path)
    app = create_app(cache=cache, backend=backend, token=token)

    lan_ip = _get_lan_ip()
    print(f"HushCast Desktop Worker v0.1.0 ({detect_accelerator().upper()})")
    print(f"  URL:           http://{lan_ip}:{args.port}")
    print(f"  Pairing Token: {token}")
    print(f"  Models Ready:  {backend.models_ready()} (dir: {backend.models_dir})")
    print(f"  Whisper Model: {backend.whisper_model_name()}")
    print(f"  Chat Model:    {backend.chat_model_path().name if backend.chat_model_ready() else 'Extractive fallback (drop any Instruct GGUF in models dir to enable LLM summaries)'}")

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
