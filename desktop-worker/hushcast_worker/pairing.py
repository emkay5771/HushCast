from __future__ import annotations

import ipaddress
import socket
from typing import Optional
from urllib.parse import quote


def get_lan_ip() -> Optional[str]:
    """Best-effort detection of this machine's non-loopback LAN IPv4 address."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            if ip and not ipaddress.ip_address(ip).is_loopback:
                return ip
    except Exception:
        pass

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            addr = ipaddress.ip_address(ip)
            if not addr.is_loopback and not addr.is_link_local:
                return ip
    except Exception:
        pass

    return None


def build_pairing_uri(url: str, token: str) -> str:
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
