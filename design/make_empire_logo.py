"""Empire TV logo (option A): gold Art Deco E over a sunburst; EMPIRE / TELEVISION wordmark.
Run from the repo root: python3 design/make_empire_logo.py"""
import math
from PIL import Image, ImageDraw, ImageFont

GOLD = (0xD4, 0xAF, 0x37)
NAVY = (0x0E, 0x12, 0x20)
LIME = "design/fonts/Limelight-Regular.ttf"
SANS = "app/src/main/res/font/space_grotesk_bold.ttf"
RES = "app/src/main/res"

def rays(d, cx, cy, ends, alpha, width, scale):
    for (x, y) in ends:
        d.line([(cx * scale, cy * scale), (x * scale, y * scale)], fill=GOLD + (alpha,), width=width)

def spaced(d, text, font, cy, cx, spacing, fill):
    widths = [d.textlength(ch, font=font) for ch in text]
    total = sum(widths) + spacing * (len(text) - 1)
    x = cx - total / 2
    for ch, w in zip(text, widths):
        d.text((x, cy), ch, font=font, fill=fill, anchor="lm")
        x += w + spacing

def icon(size, path, rounded=True):
    S = 4; W = size * S
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    bg = Image.new("RGBA", (W, W), NAVY + (255,))
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=int(W * 0.11) if rounded else 0, fill=255)
    img.paste(bg, (0, 0), mask)
    lay = Image.new("RGBA", (W, W), (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    k = W / 360
    ends = [(462, 227), (410, 137), (330, 70), (232, 35), (128, 35), (30, 70), (-50, 137), (-102, 227)]
    for (x, y) in ends:
        d.line([(180 * k, 330 * k), (x * k, y * k)], fill=GOLD + (90,), width=max(2, int(2 * k)))
    d.rounded_rectangle([18 * k, 18 * k, 342 * k, 342 * k], radius=26 * k, outline=GOLD + (153,), width=max(2, int(2 * k)))
    f = ImageFont.truetype(LIME, int(210 * k))
    d.text((180 * k, 262 * k), "E", font=f, fill=GOLD + (255,), anchor="ms")
    lay2 = Image.new("RGBA", (W, W), (0, 0, 0, 0)); lay2.paste(lay, (0, 0), mask)
    img = Image.alpha_composite(img, lay2)
    img.resize((size, size), Image.LANCZOS).save(path)

def banner(w, h, path):
    S = 3; W, H = w * S, h * S
    img = Image.new("RGBA", (W, H), NAVY + (255,))
    k = W / 640
    rl = Image.new("RGBA", (W, H), (0, 0, 0, 0)); rd = ImageDraw.Draw(rl)
    for (x, y) in [(700, 250), (620, 90), (470, 0), (370, -20), (270, -20), (170, 0), (20, 90), (-60, 250)]:
        rd.line([(320 * k, 380 * k), (x * k, y * k)], fill=GOLD + (56,), width=max(2, int(2 * k)))
    img = Image.alpha_composite(img, rl); d = ImageDraw.Draw(img)
    d.rectangle([(320 - 150) * k, 92 * k, (320 + 150) * k, 94 * k], fill=GOLD + (180,))
    d.rectangle([(320 - 150) * k, 268 * k, (320 + 150) * k, 270 * k], fill=GOLD + (180,))
    spaced(d, "EMPIRE", ImageFont.truetype(LIME, int(92 * k)), 168 * k, 320 * k, 10 * k, GOLD + (255,))
    spaced(d, "TELEVISION", ImageFont.truetype(SANS, int(22 * k)), 228 * k, 320 * k, 14 * k, GOLD + (255,))
    img.convert("RGB").resize((w, h), Image.LANCZOS).save(path)

def wordmark(w, h, path):
    S = 3; W, H = w * S, h * S
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    spaced(d, "EMPIRE", ImageFont.truetype(LIME, int(H * 0.62)), H * 0.38, W / 2, H * 0.06, GOLD + (255,))
    spaced(d, "TELEVISION", ImageFont.truetype(SANS, int(H * 0.17)), H * 0.86, W / 2, H * 0.1, GOLD + (255,))
    img.resize((w, h), Image.LANCZOS).save(path)

icon(192, f"{RES}/drawable-xxxhdpi/ic_launcher.png")
icon(96, f"{RES}/drawable-xxhdpi/empire_mark.png")
banner(640, 360, f"{RES}/drawable-xhdpi/banner.png")
wordmark(516, 150, f"{RES}/drawable-xxhdpi/logo_wordmark.png")
print("done")
