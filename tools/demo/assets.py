"""Props for the demo: the plumber's quote Hearth sends as a photo, and Hearth's voice on the call.

    DEMO_GEMINI_KEY_FILE=/path/outside/the/repo uv run --with pillow python assets.py

The voice uses the same Gemini text-to-speech the relay uses for voice notes (model and voice below).
"""
import base64
import json
import os
import urllib.request
import wave
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

S = Path(__file__).parent
CALL_LINE = ("Hi, it's Hearth. The plumber just called: the part for the sink won't arrive until Thursday. "
             "I can move him to Thursday at ten, or find someone else for tomorrow. Which do you prefer?")
MODEL = os.environ.get("HIVEPAL_TTS_MODEL", "gemini-3.8-flash-tts")
VOICE = "Puck"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    noto = "/usr/share/fonts/google-noto-vf/NotoSans[wght].ttf"
    if Path(noto).exists():
        f = ImageFont.truetype(noto, size)
        f.set_variation_by_axes([700 if bold else 400])
        return f
    for p in ("/usr/share/fonts/liberation-sans-fonts/LiberationSans-Bold.ttf" if bold
              else "/usr/share/fonts/liberation-sans-fonts/LiberationSans-Regular.ttf",):
        if Path(p).exists():
            return ImageFont.truetype(p, size)
    raise SystemExit("no Noto Sans or Liberation Sans font")


def quote() -> None:
    """A paper quote on a wooden table, as if photographed with the phone."""
    img = Image.new("RGB", (900, 1200), "#8a6a4a")
    d = ImageDraw.Draw(img)
    for y in range(0, 1200, 9):  # wood grain
        d.line([(0, y), (900, y + 14)], fill=("#866548" if (y // 9) % 2 else "#8f6f4f"), width=5)
    paper = Image.new("RGB", (640, 900), "#fbfaf6")
    p = ImageDraw.Draw(paper)
    p.text((48, 46), "Rivera Plumbing", font=font(40, True), fill="#1d2b3a")
    p.text((48, 98), "QUOTE  #0427", font=font(24), fill="#6b7785")
    p.line([(48, 146), (592, 146)], fill="#d6d9de", width=2)
    rows = [("Kitchen sink: replace trap", "€45.00"), ("Seal and pressure test", "€20.00"), ("Call-out (morning)", "€20.00")]
    y = 180
    for label, price in rows:
        p.text((48, y), label, font=font(26), fill="#24313f")
        p.text((592 - p.textlength(price, font=font(26)), y), price, font=font(26), fill="#24313f")
        y += 58
    p.line([(48, y + 8), (592, y + 8)], fill="#24313f", width=3)
    p.text((48, y + 28), "Total", font=font(32, True), fill="#1d2b3a")
    total = "€85.00"
    p.text((592 - p.textlength(total, font=font(32, True)), y + 28), total, font=font(32, True), fill="#1d2b3a")
    p.text((48, y + 110), "Tomorrow 10:00 · VAT included", font=font(24), fill="#6b7785")
    p.text((48, 800), "Thank you!", font=font(28, True), fill="#2f7d5b")
    paper = paper.rotate(-4, expand=True, fillcolor="#8a6a4a")
    img.paste(paper, (110, 120))
    img.save(S / "quote.jpg", quality=88)


def voice() -> None:
    key = open(os.environ["DEMO_GEMINI_KEY_FILE"]).read().strip()
    body = {
        "contents": [{"parts": [{"text": CALL_LINE}]}],
        "generationConfig": {"responseModalities": ["AUDIO"],
                             "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": VOICE}}}},
    }
    req = urllib.request.Request(f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent",
                                 data=json.dumps(body).encode(), headers={"Content-Type": "application/json",
                                                                         "x-goog-api-key": key})
    with urllib.request.urlopen(req, timeout=120) as r:
        res = json.load(r)
    part = next(p for p in res["candidates"][0]["content"]["parts"] if "inlineData" in p)["inlineData"]
    pcm = base64.b64decode(part["data"])
    rate = int(part["mimeType"].split("rate=")[1].split(";")[0]) if "rate=" in part["mimeType"] else 24000
    with wave.open(str(S / "hearth.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)


if __name__ == "__main__":
    import sys
    quote()
    if "--no-voice" not in sys.argv:
        voice()
    print("quote.jpg, hearth.wav")
