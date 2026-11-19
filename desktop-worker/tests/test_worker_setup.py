"""V19 / V21 / V24: verified model downloads, Mac `setup` profiles, launchd plist, TLS."""

from __future__ import annotations

import contextlib
import hashlib
import inspect
import plistlib
import re
import shutil
import subprocess
from pathlib import Path

import pytest

import hushcast_worker.cli as cli
import hushcast_worker.storage as storage
from hushcast_worker.storage import ModelVerifier, WorkerCache, download_verified

# ---- download_verified / ModelVerifier --------------------------------------------------------


class _FakeResponse:
    def __init__(self, status_code: int, body: bytes, headers: dict) -> None:
        self.status_code = status_code
        self._body = body
        self.headers = headers

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def iter_bytes(self, chunk_size: int = 1024):
        for i in range(0, len(self._body), 3):
            yield self._body[i : i + 3]


class _FakeServer:
    """Serves one body over a fake `httpx.stream`, honouring Range like Hugging Face does."""

    def __init__(self, body: bytes, *, honour_range: bool = True) -> None:
        self.body = body
        self.honour_range = honour_range
        self.requests: list[dict] = []

    @contextlib.contextmanager
    def stream(self, method, url, headers=None, **kwargs):
        headers = headers or {}
        self.requests.append(dict(headers))
        rng = headers.get("Range")
        if rng and self.honour_range:
            start = int(re.match(r"bytes=(\d+)-", rng).group(1))
            if start >= len(self.body):
                yield _FakeResponse(416, b"", {})
                return
            part = self.body[start:]
            yield _FakeResponse(206, part, {"content-length": str(len(part))})
            return
        yield _FakeResponse(200, self.body, {"content-length": str(len(self.body))})


@pytest.fixture
def fake_http(monkeypatch):
    import httpx

    def _install(body: bytes, **kwargs) -> _FakeServer:
        server = _FakeServer(body, **kwargs)
        monkeypatch.setattr(httpx, "stream", server.stream)
        return server

    return _install


def test_download_verified_fresh_then_cached(tmp_path: Path, fake_http) -> None:
    body = b"model-bytes-" * 50
    sha = hashlib.sha256(body).hexdigest()
    server = fake_http(body)
    verifier = ModelVerifier(tmp_path)
    dest = tmp_path / "m.bin"
    progress: list[tuple[int, int]] = []

    assert download_verified("https://x/m.bin", dest, sha256=sha, size=len(body), verifier=verifier,
                             on_progress=lambda r, t: progress.append((r, t))) is True
    assert dest.read_bytes() == body
    assert not (tmp_path / "m.bin.part").exists()
    assert progress[-1] == (len(body), len(body))
    assert verifier.is_verified(dest, sha)

    # A second run trusts the manifest: no request, no re-hash.
    hashes: list[Path] = []
    real = storage.sha256_file
    storage.sha256_file = lambda p: hashes.append(p) or real(p)
    try:
        assert download_verified("https://x/m.bin", dest, sha256=sha, size=len(body), verifier=ModelVerifier(tmp_path)) is False
    finally:
        storage.sha256_file = real
    assert hashes == [] and len(server.requests) == 1


def test_download_verified_rejects_bad_checksum(tmp_path: Path, fake_http) -> None:
    fake_http(b"tampered")
    dest = tmp_path / "m.bin"
    with pytest.raises(ValueError, match="Checksum mismatch for m.bin"):
        download_verified("https://x/m.bin", dest, sha256="0" * 64, verifier=ModelVerifier(tmp_path))
    assert not dest.exists() and not (tmp_path / "m.bin.part").exists()


def test_download_verified_replaces_unverified_legacy_file(tmp_path: Path, fake_http) -> None:
    body = b"new-revision"
    sha = hashlib.sha256(body).hexdigest()
    dest = tmp_path / "m.bin"
    dest.write_bytes(b"old-revision")  # e.g. a file fetched from resolve/main before pinning
    fake_http(body)
    assert download_verified("https://x/m.bin", dest, sha256=sha, verifier=ModelVerifier(tmp_path)) is True
    assert dest.read_bytes() == body


def test_download_verified_resumes_and_recovers_from_stale_part(tmp_path: Path, fake_http) -> None:
    body = bytes(range(256)) * 4
    sha = hashlib.sha256(body).hexdigest()
    dest = tmp_path / "m.bin"

    (tmp_path / "m.bin.part").write_bytes(body[:300])
    server = fake_http(body)
    assert download_verified("https://x/m.bin", dest, sha256=sha, size=len(body)) is True
    assert server.requests == [{"Range": "bytes=300-"}]
    assert dest.read_bytes() == body

    # A partial file from another revision fails the hash, then restarts from zero once.
    dest.unlink()
    (tmp_path / "m.bin.part").write_bytes(b"\xff" * 300)
    server = fake_http(body)
    assert download_verified("https://x/m.bin", dest, sha256=sha, size=len(body)) is True
    assert server.requests == [{"Range": "bytes=300-"}, {}]

    # A server that ignores Range sends the whole file; the hash restarts with it.
    dest.unlink()
    (tmp_path / "m.bin.part").write_bytes(body[:300])
    server = fake_http(body, honour_range=False)
    assert download_verified("https://x/m.bin", dest, sha256=sha, size=len(body)) is True
    assert dest.read_bytes() == body


def test_delete_audio_keeps_bundle_and_index(tmp_path: Path) -> None:
    from hushcast_worker.models import DesktopAnalysisBundle, WordIndexBlobDto

    cache = WorkerCache(tmp_path / "cache")
    sha = "d" * 64
    cache.audio_path(sha).write_bytes(b"AUDIO")
    bundle = DesktopAnalysisBundle(
        fileSha256=sha, episodeId="ep", durationSec=1.0,
        wordIndexBlob=WordIndexBlobDto(words=[], segments=[]), adSegments=[],
    )
    cache.save_episode(sha256=sha, audio_tmp_path=None, bundle=bundle, audio_url="https://x/a.mp3", title="A")
    assert cache._index["by_sha"][sha]["hasAudio"] is True

    assert cache.delete_audio(sha, update_index=False) is True
    assert cache._index["by_sha"][sha]["hasAudio"] is True  # left for the other writer
    cache.audio_path(sha).write_bytes(b"AUDIO")
    assert cache.delete_audio(sha) is True
    assert cache.delete_audio(sha) is False
    assert cache._index["by_sha"][sha]["hasAudio"] is False
    assert WorkerCache(tmp_path / "cache")._index["by_sha"][sha]["audioBytes"] == 0
    assert cache.get_bundle(sha) is not None
    assert cache.lookup_by_url("https://x/a.mp3") is None  # needs audio
    assert cache.lookup_by_url("https://x/a.mp3", require_audio=False) == sha


# ---- V21: setup profiles -----------------------------------------------------------------------


def test_every_setup_model_is_pinned_and_checksummed() -> None:
    files = {m for profile in cli.PROFILES.values() for m in profile} | {cli.MMS_FA_CHECKPOINT}
    for model in files:
        assert re.fullmatch(r"[0-9a-f]{64}", model.sha256), model
        assert model.size > 0
        assert "/resolve/main/" not in model.url, model.url
        if "huggingface.co" in model.url:
            assert re.search(r"/resolve/[0-9a-f]{40}/", model.url), model.url
        assert model.url.endswith("/" + model.filename)
    # The old digest for base.en never matched the file Hugging Face serves.
    assert cli.WHISPER_BASE_EN.sha256 == "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
    for profile in cli.PROFILES.values():
        assert cli.QWEN_EMBED in profile
        assert all("silero" not in m.filename for m in profile)


def test_setup_downloads_each_profile_model_with_its_digest(tmp_path: Path, monkeypatch) -> None:
    calls: list[tuple[str, str, int]] = []

    def fake_download(url, dest, expected_sha, size=None, verifier=None):
        calls.append((Path(dest).name, expected_sha, size))
        return True

    monkeypatch.setattr(cli, "_download_with_resume", fake_download)
    monkeypatch.setattr(cli, "_print_service_snippet", lambda *a: None)

    class _Backend:
        def __init__(self, *a, **k) -> None:
            pass

        def ml_imports_ready(self):
            return True

        def models_ready(self):
            return True

    monkeypatch.setattr(cli, "LocalMlBackend", _Backend)
    assert cli._run_setup(tmp_path, None, "quality", skip_smoke=True) == 0
    expected = [(m.filename, m.sha256, m.size) for m in cli.PROFILES["quality"]]
    try:
        import torch  # noqa: F401
        import torchaudio  # noqa: F401
        expected.append((cli.MMS_FA_CHECKPOINT.filename, cli.MMS_FA_CHECKPOINT.sha256, cli.MMS_FA_CHECKPOINT.size))
    except Exception:
        pass
    assert calls == expected


def test_launchd_plist_has_keepalive_logs_and_env(tmp_path: Path) -> None:
    args = ["/usr/local/bin/hushcast-worker", "--port", "8765", "--data-dir", str(tmp_path)]
    plist = plistlib.loads(cli.build_launchd_plist(args, tmp_path, env={"HUSHCAST_MAX": "1"}).encode())
    assert plist["Label"] == "com.hushcast.worker"
    assert plist["ProgramArguments"] == args
    assert plist["KeepAlive"] is True and plist["RunAtLoad"] is True
    assert plist["StandardOutPath"] == str(tmp_path / "logs" / "worker.log")
    assert plist["StandardErrorPath"] == str(tmp_path / "logs" / "worker.err.log")
    env = plist["EnvironmentVariables"]
    assert "/opt/homebrew/bin" in env["PATH"] and env["PYTHONUNBUFFERED"] == "1" and env["HUSHCAST_MAX"] == "1"
    assert plist["WorkingDirectory"] == str(tmp_path)

    unit = cli.build_systemd_unit(args, tmp_path)
    assert "Restart=always" in unit and f"ExecStart={' '.join(args)}" in unit


# ---- V24: TLS ----------------------------------------------------------------------------------


def _self_signed(tmp_path: Path) -> tuple[Path, Path]:
    openssl = shutil.which("openssl")
    if not openssl:
        pytest.skip("openssl not available")
    cert, key = tmp_path / "cert.pem", tmp_path / "key.pem"
    subprocess.run(
        [openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
         "-subj", "/CN=hushcast.local", "-keyout", str(key), "-out", str(cert)],
        check=True, capture_output=True,
    )
    return cert, key


def test_tls_options_validate_and_map_to_uvicorn(tmp_path: Path) -> None:
    import uvicorn

    assert cli.tls_options(None, None) == {}
    with pytest.raises(ValueError, match="together"):
        cli.tls_options(tmp_path / "c.pem", None)
    with pytest.raises(ValueError, match="not found"):
        cli.tls_options(tmp_path / "c.pem", tmp_path / "k.pem")

    cert, key = _self_signed(tmp_path)
    opts = cli.tls_options(cert, key)
    assert opts == {"ssl_certfile": str(cert), "ssl_keyfile": str(key)}
    assert set(opts) <= set(inspect.signature(uvicorn.run).parameters)

    with pytest.raises(ValueError, match="could not be loaded"):
        cli.tls_options(cert, cert)  # a cert is not a key

    fp = cli.cert_sha256_fingerprint(cert)
    der = subprocess.run(["openssl", "x509", "-in", str(cert), "-outform", "DER"], check=True, capture_output=True).stdout
    assert fp.replace(":", "").lower() == hashlib.sha256(der).hexdigest()
