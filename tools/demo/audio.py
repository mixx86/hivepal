"""Sound for the demo, from the same event log: key clicks while typing, a chime when the phone gets a
message, a softer tick for a reminder, the ringtone while it rings, the agent's voice once answered and
a hang-up tone. A very quiet pad underneath so the silences don't feel dead."""
import json
import subprocess
import wave
from pathlib import Path

import numpy as np

S = Path(__file__).parent
SR = 48000
data = json.loads((S / "events.json").read_text())
lead = data["lead"]
ev = data["events"]
dur = ev[-1]["t"] + lead + 1.5
out = np.zeros(int(dur * SR), dtype=np.float64)
rng = np.random.default_rng(7)


def at(t: float, x: np.ndarray, gain: float = 1.0) -> None:
    i = int(t * SR)
    j = min(len(out), i + len(x))
    if i < len(out):
        out[i:j] += gain * x[: j - i]


def env(n: int, decay: float) -> np.ndarray:
    return np.exp(-np.arange(n) / (decay * SR))


def tone(freqs, length: float, decay: float, attack: float = 0.004) -> np.ndarray:
    n = int(length * SR)
    t = np.arange(n) / SR
    x = sum(np.sin(2 * np.pi * f * t) * (0.6 ** k) for k, f in enumerate(freqs))
    a = np.minimum(1, t / attack)
    return x * a * env(n, decay)


def click() -> np.ndarray:
    n = int(0.012 * SR)
    noise = rng.standard_normal(n)
    noise = np.diff(noise, prepend=0)  # brighter, like a key
    body = np.sin(2 * np.pi * rng.uniform(1800, 2600) * np.arange(n) / SR)
    return (0.6 * noise + 0.4 * body) * env(n, 0.003)


chime = tone([1046.5, 1568.0, 2093.0], 0.9, 0.22) + np.concatenate(
    [np.zeros(int(0.09 * SR)), tone([1318.5, 1975.5], 0.81, 0.2)])[: int(0.9 * SR)]
tick = tone([880.0, 1760.0], 0.4, 0.08)
note = lambda f: tone([f, f * 2, f * 3], 0.45, 0.12)  # marimba-ish

ring_start = ring_end = None
for i, e in enumerate(ev):
    t = e["t"] + lead
    if e["type"] == "cmd":
        n = len(e["text"])
        for k in range(n):
            if e["text"][k] == " " and rng.random() < 0.5:
                continue
            at(t + e["typing"] * k / n + rng.uniform(-0.01, 0.01), click(), rng.uniform(0.05, 0.09))
        at(t + e["typing"] + 0.25, click(), 0.12)  # enter
    elif e["type"] == "out":
        txt = e["text"]
        if txt.startswith("msg_"):
            at(t + 0.15, chime, 0.22)
        elif txt.startswith("rem_"):
            at(t + 0.1, tick, 0.18)
        elif "ringing on" in txt:
            ring_start = t
        elif "answered on" in txt:
            ring_end = t
        elif txt.startswith("Call ended") or txt.startswith("Call missed"):
            at(t - 0.4, tone([660.0], 0.18, 0.08), 0.2)
            at(t - 0.18, tone([495.0], 0.25, 0.1), 0.2)

# Ringtone: a little rising figure every 1.6 s while it rings
if ring_start is not None:
    end = ring_end if ring_end is not None else ring_start + 8
    t = ring_start + 0.2
    while t < end - 0.2:
        for k, f in enumerate((659.3, 784.0, 987.8, 784.0)):
            at(t + k * 0.13, note(f), 0.22)
        t += 1.6

# The agent's voice once answered
if ring_end is not None:
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(S / "hearth.wav"), "-ar", str(SR), "-ac", "1",
                    "-f", "f32le", str(S / "hearth.f32")], check=True)
    voice = np.fromfile(S / "hearth.f32", dtype=np.float32).astype(np.float64)
    # Text-to-speech sometimes ends with a short burst of noise after the last word: cut whatever
    # follows the last pause (>= 0.1 s under 1 % of full scale) if it's shorter than 0.4 s, then fade.
    w = int(0.05 * SR)
    rms = np.sqrt(np.convolve(voice ** 2, np.ones(w) / w, mode="same"))
    quiet = rms < 0.01
    end = len(voice)
    run = 0
    for i in range(len(voice) - 1, -1, -w // 2):
        run = run + w // 2 if quiet[i] else 0
        if run >= int(0.1 * SR):
            if len(voice) - i < int(0.6 * SR):
                end = i
            break
    voice = voice[:end]
    voice[-int(0.03 * SR):] *= np.linspace(1, 0, int(0.03 * SR))
    voice /= max(1e-9, np.abs(voice[: int(len(voice) * 0.98)]).max())
    at(ring_end + 0.25, tone([1200.0], 0.12, 0.04), 0.12)  # connected
    at(ring_end + 0.7, voice, 0.75)

# Quiet pad (A minor 9, slow swell) under everything, ducked while the voice talks
t = np.arange(len(out)) / SR
pad = sum(np.sin(2 * np.pi * f * t + p) for f, p in ((110.0, 0), (164.8, 1.1), (220.0, 2.3), (246.9, 0.7), (329.6, 1.9)))
pad *= 0.010 * (0.6 + 0.4 * np.sin(2 * np.pi * t / 9.0))
fade = np.minimum(1, t / 2.0) * np.minimum(1, (dur - t) / 2.0)
if ring_end is not None:
    duck = np.ones_like(t)
    duck[(t > ring_end + 0.5) & (t < ring_end + 11.5)] = 0.35
    pad *= duck
out += pad * fade

out /= max(1.0, np.abs(out).max() / 0.9)
pcm = (out * 32767).astype(np.int16)
with wave.open(str(S / "demo-audio.wav"), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print("audio", round(dur, 1), "s; ring", ring_start, "answer", ring_end)
