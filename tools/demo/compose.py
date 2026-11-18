"""Renders the terminal from events.json frame by frame and puts the phone recording beside it."""
import json
import re
import subprocess
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = Path(__file__).parent
OUT = S / "frames"
FPS = 15
W, H = 1280, 720
TERM = (36, 56, 860, 672)        # x0, y0, x1, y1
PHONE = (916, 34, 1244, 694)     # bezel box; the screen goes inside with a 10 px margin
FONT = ImageFont.truetype("/usr/share/fonts/adobe-source-code-pro-fonts/SourceCodePro-Medium.otf", 17)
BOLD = ImageFont.truetype("/usr/share/fonts/adobe-source-code-pro-fonts/SourceCodePro-Bold.otf", 17) \
    if Path("/usr/share/fonts/adobe-source-code-pro-fonts/SourceCodePro-Bold.otf").exists() else FONT
SMALL = ImageFont.truetype("/usr/share/fonts/adobe-source-code-pro-fonts/SourceCodePro-Medium.otf", 14)
EMOJI = re.compile("[\U0001F300-\U0001FAFF☀-➿]")
CW = FONT.getlength("M")
LH = 26
COLS = int((TERM[2] - TERM[0] - 40) // CW)
AGENT = {"nubi": "#00A6FB", "coach": "#3DFFA8", "ink": "#B8C0FF", "main": "#FFD60A"}
INK, NAVY, BLUE, CYAN, TEXT, MUTED = "#000814", "#001D3D", "#00A6FB", "#7DF9FF", "#E6F4FF", "#8FB3D9"

data = json.loads((S / "events.json").read_text())
lead = data["lead"]
events = data["events"]
end = events[-1]["t"] + lead + 0.5


def clean(s: str) -> str:
    return re.sub(r"\s*(?:" + EMOJI.pattern + r")+\ufe0f?", "", s).rstrip()


def tidy_out(s: str) -> list[str]:
    lines = []
    for raw in s.splitlines():
        raw = re.sub(r" {3,}", "  ", raw.rstrip())
        lines += textwrap.wrap(clean(raw), COLS - 2, subsequent_indent="  ") or [""]
    return lines


def state(t: float) -> list[tuple]:
    """Terminal lines at video time t: (kind, who, text)."""
    lines: list[tuple] = []
    for e in events:
        et = e["t"] + lead
        if et > t or e["type"] == "end":
            continue
        if e["type"] == "cmd":
            if lines:
                lines.append(("blank", None, ""))
            n = len(e["text"])
            shown = e["text"][: max(0, min(n, int(n * (t - et) / e["typing"])))]
            prefix = len(e["who"]) + 3
            wrapped = textwrap.wrap(clean(shown), COLS - prefix, subsequent_indent="  ") or [""]
            typing = t - et < e["typing"] + 0.3
            for i, w in enumerate(wrapped):
                lines.append(("cmd" if i == 0 else "cmdcont", e["who"], w + ("▌" if typing and i == len(wrapped) - 1 else "")))
        elif e["type"] == "out":
            for w in tidy_out(e["text"]):
                lines.append(("out", None, w))
    return lines


def base() -> Image.Image:
    img = Image.new("RGB", (W, H), INK)
    d = ImageDraw.Draw(img)
    for y in range(H):  # vertical gradient
        k = y / H
        d.line([(0, y), (W, y)], fill=(0, int(8 + 21 * k), int(20 + 41 * k)))
    # soft glow behind the phone
    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).ellipse((PHONE[0] - 60, PHONE[1] + 60, PHONE[2] + 60, PHONE[3] - 40), fill=90)
    glow = glow.filter(ImageFilter.GaussianBlur(70))
    img.paste(Image.new("RGB", (W, H), BLUE), (0, 0), glow)
    d = ImageDraw.Draw(img)
    # terminal window
    d.rounded_rectangle(TERM, 16, fill="#050B16", outline="#123456", width=2)
    d.rounded_rectangle((TERM[0], TERM[1], TERM[2], TERM[1] + 38), 16, fill="#0B1A30")
    d.rectangle((TERM[0], TERM[1] + 22, TERM[2], TERM[1] + 38), fill="#0B1A30")
    for i, c in enumerate(("#FF6B8B", "#FFD60A", "#3DFFA8")):
        d.ellipse((TERM[0] + 18 + i * 22, TERM[1] + 13, TERM[0] + 30 + i * 22, TERM[1] + 25), fill=c)
    title = "hivepal — your agents"
    d.text(((TERM[0] + TERM[2]) / 2 - SMALL.getlength(title) / 2, TERM[1] + 10), title, font=SMALL, fill=MUTED)
    # phone bezel
    d.rounded_rectangle(PHONE, 40, fill="#0A1222", outline="#1F4E7A", width=3)
    return img


def frame(t: float, bg: Image.Image) -> Image.Image:
    img = bg.copy()
    d = ImageDraw.Draw(img)
    lines = state(t)
    rows = int((TERM[3] - TERM[1] - 56) // LH)
    lines = lines[-rows:]
    y = TERM[1] + 52
    x0 = TERM[0] + 20
    for kind, who, text in lines:
        if kind == "cmd":
            d.text((x0, y), who, font=BOLD, fill=AGENT.get(who, CYAN))
            x = x0 + CW * (len(who) + 1)
            d.text((x, y), "$", font=FONT, fill=CYAN)
            x += CW * 2
            if text.startswith("hivepal"):
                d.text((x, y), "hivepal", font=BOLD, fill=BLUE)
                d.text((x + CW * 7, y), text[7:], font=FONT, fill=TEXT)
            else:
                d.text((x, y), text, font=FONT, fill=TEXT)
        elif kind == "cmdcont":
            d.text((x0 + CW * (len(who) + 3), y), text, font=FONT, fill=TEXT)
        elif kind == "out":
            col = "#3DFFA8" if re.match(r"^(msg|rem)_", text) else MUTED
            d.text((x0, y), text, font=FONT, fill=col)
        y += LH
    return img


def main() -> None:
    OUT.mkdir(exist_ok=True)
    for f in OUT.glob("*.png"):
        f.unlink()
    bg = base()
    n = int(end * FPS)
    for i in range(n):
        frame(i / FPS, bg).save(OUT / f"{i:05d}.png", compress_level=1)
    # rounded mask for the phone screen
    sx0, sy0, sx1, sy1 = PHONE[0] + 10, PHONE[1] + 10, PHONE[2] - 10, PHONE[3] - 10
    mask = Image.new("L", (sx1 - sx0, sy1 - sy0), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, sx1 - sx0 - 1, sy1 - sy0 - 1), 32, fill=255)
    mask.save(S / "phone_mask.png")
    sw, sh = sx1 - sx0, sy1 - sy0
    vf = (f"[1:v]fps={FPS},scale={sw}:{sh}:flags=lanczos,format=rgba[ph];[2:v]format=gray[m];[ph][m]alphamerge[phm];"
          f"[0:v][phm]overlay={sx0}:{sy0}:shortest=1,format=yuv420p[v]")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-framerate", str(FPS), "-i", str(OUT / "%05d.png"),
                    "-i", str(S / "phone.mp4"), "-loop", "1", "-i", str(S / "phone_mask.png"),
                    "-filter_complex", vf, "-map", "[v]", "-c:v", "libx264", "-crf", "22", "-preset", "slow",
                    "-movflags", "+faststart", "-t", f"{end:.2f}", str(S / "hivepal-demo.mp4")], check=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(S / "hivepal-demo.mp4"), "-vf",
                    "fps=10,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];"
                    "[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle", str(S / "hivepal-demo.gif")], check=True)
    print("ok", n, "frames")


if __name__ == "__main__":
    main()
