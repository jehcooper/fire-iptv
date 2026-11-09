"""Cooper TV logo: a glowing gradient 'C' ring that wraps a play button, plus an Inter Display wordmark."""
import math, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FD = "/usr/share/fonts/opentype/inter/"
BLACK = FD + "InterDisplay-Black.otf"
LIGHT = FD + "InterDisplay-Light.otf"
BG1, BG2 = (7, 10, 16), (16, 26, 38)
G1, G2, G3 = (46, 229, 157), (34, 211, 238), (124, 92, 255)   # mint -> cyan -> violet

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def gradient(size, stops, angle_deg=35):
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    a = math.radians(angle_deg); dx, dy = math.cos(a), math.sin(a)
    proj = [x * dx + y * dy for x in (0, w) for y in (0, h)]
    lo, hi = min(proj), max(proj)
    for y in range(h):
        for x in range(w):
            t = ((x * dx + y * dy) - lo) / (hi - lo)
            if t < 0.5: c = lerp(stops[0], stops[1], t / 0.5)
            else: c = lerp(stops[1], stops[2], (t - 0.5) / 0.5)
            px[x, y] = c
    return img

def emblem(S):
    """S x S RGBA emblem."""
    k = 4; N = S * k                                   # supersample
    mask = Image.new("L", (N, N), 0)
    d = ImageDraw.Draw(mask)
    pad = N * 0.10; ring = N * 0.13
    box = [pad, pad, N - pad, N - pad]
    start, end = 42, 318                               # gap on the right (PIL angles: clockwise from 3 o'clock)
    d.arc(box, start, end, fill=255, width=int(ring))
    r = (N - 2 * pad) / 2 - ring / 2; cx = cy = N / 2
    for ang in (start, end):                           # rounded ends
        x = cx + r * math.cos(math.radians(ang)); y = cy + r * math.sin(math.radians(ang))
        d.ellipse([x - ring / 2, y - ring / 2, x + ring / 2, y + ring / 2], fill=255)
    # play triangle, nudged right into the C's mouth
    t = N * 0.40; tx = cx - t * 0.30
    tri = [(tx, cy - t / 2), (tx, cy + t / 2), (tx + t * 0.88, cy)]
    d.polygon(tri, fill=255)
    # soften triangle corners slightly
    mask = mask.filter(ImageFilter.GaussianBlur(k * 0.6))
    grad = gradient((N, N), (G1, G2, G3), 35).convert("RGBA")
    out = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    glow = Image.new("RGBA", (N, N), (0, 0, 0, 0)); glow.paste(grad, (0, 0), mask)
    glow = glow.filter(ImageFilter.GaussianBlur(N * 0.045))
    gl = glow.split()[3].point(lambda v: int(v * 0.85)); glow.putalpha(gl)
    out = Image.alpha_composite(out, glow)
    solid = Image.new("RGBA", (N, N), (0, 0, 0, 0)); solid.paste(grad, (0, 0), mask)
    out = Image.alpha_composite(out, solid)
    # glossy highlight on the upper arc
    hl = Image.new("L", (N, N), 0); hd = ImageDraw.Draw(hl)
    hd.arc([pad + ring * 0.35, pad + ring * 0.35, N - pad - ring * 0.35, N - pad - ring * 0.35], 200, 290, fill=110, width=int(ring * 0.18))
    hl = hl.filter(ImageFilter.GaussianBlur(k * 2))
    white = Image.new("RGBA", (N, N), (255, 255, 255, 0)); white.putalpha(hl)
    out = Image.alpha_composite(out, white)
    return out.resize((S, S), Image.LANCZOS)

def background(w, h, glow_at=None, glow_r=None):
    img = gradient((w, h), (BG1, BG2, BG1), 20).convert("RGBA")
    if glow_at:
        g = Image.new("RGBA", (w, h), (0, 0, 0, 0)); gd = ImageDraw.Draw(g)
        x, y = glow_at; r = glow_r
        gd.ellipse([x - r, y - r, x + r, y + r], fill=(34, 211, 238, 70))
        g = g.filter(ImageFilter.GaussianBlur(r * 0.5))
        img = Image.alpha_composite(img, g)
    return img

def wordmark(draw_on, x, y, h, spacing=0.06):
    """'COOPER' heavy + 'TV' in gradient light; returns width."""
    f1 = ImageFont.truetype(BLACK, int(h)); f2 = ImageFont.truetype(LIGHT, int(h))
    d = ImageDraw.Draw(draw_on)
    cur = x
    for ch in "COOPER":
        d.text((cur, y), ch, font=f1, fill=(240, 245, 250, 255), anchor="ls")
        cur += d.textlength(ch, font=f1) + h * spacing
    cur += h * 0.22
    tv_w = sum(d.textlength(c, font=f2) for c in "TV") + h * spacing
    tv = Image.new("RGBA", draw_on.size, (0, 0, 0, 0)); td = ImageDraw.Draw(tv)
    c2 = cur
    for ch in "TV":
        td.text((c2, y), ch, font=f2, fill=(255, 255, 255, 255), anchor="ls")
        c2 += td.textlength(ch, font=f2) + h * spacing
    grad = gradient(draw_on.size, (G1, G2, G3), 0).convert("RGBA")
    colored = Image.new("RGBA", draw_on.size, (0, 0, 0, 0)); colored.paste(grad, (0, 0), tv.split()[3])
    draw_on.alpha_composite(colored)
    return c2 - x

def text_width(h, spacing=0.06):
    f1 = ImageFont.truetype(BLACK, int(h)); f2 = ImageFont.truetype(LIGHT, int(h))
    d = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    w = sum(d.textlength(c, font=f1) + h * spacing for c in "COOPER") + h * 0.22
    w += sum(d.textlength(c, font=f2) + h * spacing for c in "TV")
    return w

out = sys.argv[1]
# --- Banner 640x360 (320x180dp @ xhdpi) ---
W, H = 640, 360
em = 150; th = 64
total = em + 26 + text_width(th)
x0 = (W - total) / 2
b = background(W, H, glow_at=(x0 + em / 2, H / 2), glow_r=150)
b.alpha_composite(emblem(em), (int(x0), int(H / 2 - em / 2)))
wordmark(b, int(x0 + em + 26), int(H / 2 + th * 0.36), th)
# thin accent line
ld = ImageDraw.Draw(b)
ld.line([(x0 + em + 30, H / 2 + th * 0.62), (x0 + total - 6, H / 2 + th * 0.62)], fill=(46, 229, 157, 90), width=2)
b.convert("RGB").save(f"{out}/banner.png")
# --- Launcher icon 192x192 ---
ic = background(192, 192, glow_at=(96, 96), glow_r=80)
ic.alpha_composite(emblem(150), (21, 21))
mask = Image.new("L", (192, 192), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, 191, 191], 40, fill=255)
icon = Image.new("RGBA", (192, 192), (0, 0, 0, 0)); icon.paste(ic, (0, 0), mask)
icon.save(f"{out}/ic_launcher.png")
# --- In-app wordmark (transparent), 132px tall @ xxhdpi = 44dp ---
H2 = 132; em2 = 120; th2 = 60
W2 = int(em2 + 20 + text_width(th2) + 10)
wm = Image.new("RGBA", (W2, H2), (0, 0, 0, 0))
wm.alpha_composite(emblem(em2), (0, (H2 - em2) // 2))
wordmark(wm, em2 + 20, int(H2 / 2 + th2 * 0.36), th2)
wm.save(f"{out}/logo_wordmark.png")
# --- Preview for the user ---
prev = background(1280, 720, glow_at=(640, 300), glow_r=300)
big = b.resize((1152, 648), Image.LANCZOS)
prev.alpha_composite(big.convert("RGBA"), (64, 36))
prev.convert("RGB").save(f"{out}/cooper_tv_logo_preview.png")
print("ok")
