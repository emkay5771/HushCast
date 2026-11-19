from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

from hushcast_worker import audio
from hushcast_worker.audio import _iter_decoded_blocks, decode_audio_streams

# --- block building (numpy path, no PyAV needed) ------------------------------------------------


class _FakeFrame:
    def __init__(self, arr: np.ndarray, fmt: str = "fltp", layout: str = "stereo", rate: int = 44100) -> None:
        self._arr = arr
        self.format = SimpleNamespace(name=fmt)
        self.layout = SimpleNamespace(name=layout)
        self.sample_rate = rate
        self.samples = arr.shape[1] if fmt.endswith("p") else arr.shape[1] // max(1, arr.shape[0])
        self.pts = None
        self.time_base = None

    def to_ndarray(self) -> np.ndarray:
        return self._arr


class _FakeAudioFrameCls:
    @staticmethod
    def from_ndarray(arr, format, layout):  # noqa: A002 - PyAV's keyword names
        return _FakeFrame(np.array(arr, copy=True), fmt=format, layout=layout)


class _FakeContainer:
    def __init__(self, frames) -> None:
        self._frames = frames

    def decode(self, audio=0):
        yield from self._frames


@pytest.fixture
def fake_av(monkeypatch):
    mod = SimpleNamespace(AudioFrame=_FakeAudioFrameCls)
    monkeypatch.setitem(sys.modules, "av", mod)
    return mod


def test_blocks_concatenate_codec_frames_in_order(fake_av) -> None:
    rng = np.random.default_rng(0)
    frames = [_FakeFrame(rng.standard_normal((2, 1152)).astype(np.float32)) for _ in range(40)]
    blocks = list(_iter_decoded_blocks(_FakeContainer(frames), block_sec=0.1))  # 4410 samples
    assert all(b.samples >= 4410 for b in blocks[:-1])
    assert len(blocks) < len(frames)
    got = np.concatenate([b.to_ndarray() for b in blocks], axis=1)
    want = np.concatenate([f.to_ndarray() for f in frames], axis=1)
    assert np.array_equal(got, want)
    # pts count samples fed so far, at the block's own rate
    assert [b.pts for b in blocks] == list(np.cumsum([0] + [b.samples for b in blocks[:-1]]))


def test_blocks_split_on_format_change(fake_av) -> None:
    a = [_FakeFrame(np.ones((2, 100), np.float32), rate=44100) for _ in range(3)]
    b = [_FakeFrame(np.zeros((2, 100), np.float32), rate=48000) for _ in range(2)]
    blocks = list(_iter_decoded_blocks(_FakeContainer(a + b), block_sec=60.0))
    assert [(blk.sample_rate, blk.samples) for blk in blocks] == [(44100, 300), (48000, 200)]


def test_unbuildable_block_falls_back_to_codec_frames(fake_av, monkeypatch) -> None:
    def _boom(arr, format, layout):  # noqa: A002
        raise ValueError("unknown layout")

    monkeypatch.setattr(fake_av.AudioFrame, "from_ndarray", staticmethod(_boom))
    frames = [_FakeFrame(np.ones((2, 64), np.float32)) for _ in range(5)]
    blocks = list(_iter_decoded_blocks(_FakeContainer(frames), block_sec=0.001))
    assert blocks == frames


# --- full decode against the frame-by-frame implementation (needs PyAV) -------------------------


def _write_test_file(path: Path, codec: str, rate: int, layout: str, seconds: float) -> None:
    av = pytest.importorskip("av")
    n_ch = 2 if layout == "stereo" else 1
    t = np.arange(int(seconds * rate)) / rate
    rng = np.random.default_rng(0)
    sig = np.stack(
        [0.3 * np.sin(2 * np.pi * (220 + 50 * c) * t) + 0.05 * rng.standard_normal(len(t)) for c in range(n_ch)]
    ).astype(np.float32)
    with av.open(str(path), "w") as out:
        st = out.add_stream(codec, rate=rate)
        st.layout = layout
        step = 1152 if codec == "mp3" else 1024
        for i in range(0, sig.shape[1], step):
            if codec.startswith("pcm_"):
                blk = (np.ascontiguousarray(sig[:, i : i + step]).T.reshape(1, -1) * 32767).astype(np.int16)
                fr = av.AudioFrame.from_ndarray(blk, format="s16", layout=layout)
            else:
                fr = av.AudioFrame.from_ndarray(np.ascontiguousarray(sig[:, i : i + step]), format="fltp", layout=layout)
            fr.sample_rate = rate
            for p in st.encode(fr):
                out.mux(p)
        for p in st.encode(None):
            out.mux(p)


def _frame_by_frame_reference(path: Path):
    """The 1.0.30 decode loop: both resamplers fed one codec frame at a time."""
    import av

    with av.open(str(path)) as c:
        stereo = int(c.streams.audio[0].channels) >= 2
        r16 = av.AudioResampler(format="flt", layout="mono", rate=16000)
        r24 = av.AudioResampler(format="flt", layout="stereo" if stereo else "mono", rate=24000)
        a16, mid, side = [], [], []

        def _h24(fr):
            arr = fr.to_ndarray()
            if stereo:
                lr = arr.reshape(-1, 2) if arr.shape[0] == 1 else arr.T
                mid.append((lr[:, 0] + lr[:, 1]) * np.float32(0.5))
                side.append((lr[:, 0] - lr[:, 1]) * np.float32(0.5))
            else:
                mid.append(arr.reshape(-1))

        for frame in c.decode(audio=0):
            a16.extend(f.to_ndarray().reshape(-1) for f in r16.resample(frame))
            for f in r24.resample(frame):
                _h24(f)
        a16.extend(f.to_ndarray().reshape(-1) for f in r16.resample(None))
        for f in r24.resample(None):
            _h24(f)
    return np.concatenate(a16), np.concatenate(mid), (np.concatenate(side) if stereo else None)


@pytest.mark.parametrize(
    "name,codec,rate,layout",
    [("st.mp3", "mp3", 44100, "stereo"), ("mono.m4a", "aac", 48000, "mono"), ("st24.wav", "pcm_s16le", 24000, "stereo")],
)
def test_decode_matches_frame_by_frame(tmp_path: Path, name: str, codec: str, rate: int, layout: str) -> None:
    pytest.importorskip("av")
    path = tmp_path / name
    _write_test_file(path, codec, rate, layout, 45.0)
    ref16, ref_mid, ref_side = _frame_by_frame_reference(path)

    for block_sec in (audio.RESAMPLE_BLOCK_SEC, 0.5):
        pcm16, mid, side, dur = decode_audio_streams(path, block_sec=block_sec)
        assert pcm16.dtype == np.float32 and mid.dtype == np.float32
        assert np.array_equal(pcm16, ref16)
        assert np.array_equal(mid, ref_mid)
        if ref_side is None:
            assert side is None
        else:
            assert np.array_equal(side, ref_side)
        assert dur == len(ref16) / 16000.0
