from __future__ import annotations

import ipaddress
import platform
import re
import socket
import subprocess
from typing import Optional
from urllib.parse import quote


def _is_tunnel_or_virtual_iface(name: str) -> bool:
    low = name.lower()
    return low.startswith(("utun", "tun", "tap", "wg", "ppp", "docker", "veth", "br-", "lo", "vmnet"))


def _is_preferred_lan_iface(name: str) -> bool:
    low = name.lower()
    return low.startswith(("en", "eth", "wlan", "wl"))


def get_lan_ips() -> list[str]:
    """
    Enumerates non-loopback IPv4 LAN addresses, preferring physical Wi-Fi/Ethernet
    RFC1918 addresses over VPN/tunnel interfaces (V11).
    """
    candidates: list[tuple[int, str]] = []  # (priority, ip)
    seen: set[str] = set()

    def _add(ip: str, iface: str = "") -> None:
        if ip in seen:
            return
        try:
            addr = ipaddress.ip_address(ip)
        except ValueError:
            return
        if addr.version != 4 or addr.is_loopback or addr.is_link_local or addr.is_multicast or addr.is_unspecified:
            return
        seen.add(ip)
        # Priority: 0 = preferred physical iface + RFC1918, 1 = RFC1918 non-tunnel, 2 = other private, 3 = tunnel/other
        is_cgnat = ipaddress.ip_address(ip) in ipaddress.ip_network("100.64.0.0/10")
        if iface and _is_tunnel_or_virtual_iface(iface):
            prio = 3
        elif addr.is_private and not is_cgnat and iface and _is_preferred_lan_iface(iface):
            prio = 0
        elif addr.is_private and not is_cgnat:
            prio = 1
        elif addr.is_private:
            prio = 2
        else:
            prio = 3
        candidates.append((prio, ip))

    # 1. Interface-aware enumeration via `ip` (Linux) or `ifconfig` (macOS/BSD)
    try:
        sys_name = platform.system()
        if sys_name == "Linux":
            proc = subprocess.run(["ip", "-4", "-o", "addr", "show"], capture_output=True, text=True, timeout=2, check=False)
            if proc.returncode == 0:
                for line in proc.stdout.splitlines():
                    m = re.match(r"^\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)/", line.strip())
                    if m:
                        _add(m.group(2), m.group(1))
        elif sys_name == "Darwin":
            proc = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=2, check=False)
            if proc.returncode == 0:
                cur_iface = ""
                for line in proc.stdout.splitlines():
                    m_if = re.match(r"^([A-Za-z0-9_]+):", line)
                    if m_if:
                        cur_iface = m_if.group(1)
                    else:
                        m_ip = re.search(r"\binet\s+(\d+\.\d+\.\d+\.\d+)\b", line)
                        if m_ip:
                            _add(m_ip.group(1), cur_iface)
    except Exception:
        pass

    # 2. Default-route socket probe fallback
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            _add(s.getsockname()[0])
    except Exception:
        pass

    # 3. Hostname resolution fallback
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            _add(info[4][0])
    except Exception:
        pass

    candidates.sort(key=lambda item: item[0])
    return [ip for _, ip in candidates]


def get_lan_ip() -> Optional[str]:
    """Best-effort detection of this machine's preferred non-loopback LAN IPv4 address."""
    ips = get_lan_ips()
    return ips[0] if ips else None


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
        print("\n  Scan with your phone's Camera or HushCast Settings -> Scan QR:")
        qr.print_ascii(invert=True)
    except Exception:
        pass
