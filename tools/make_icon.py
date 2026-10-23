#!/usr/bin/env python3
"""Generate the Android launcher icon (same buddy as the app) in all densities."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android" / "app" / "src" / "main" / "res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 8  # supersample


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def draw_icon(px: int) -> Image.Image:
    s = px * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # rounded navy tile
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=s * 0.235, fill=(13, 17, 29, 255))

    # warm ambient glow
    glow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(26, 0, -1):
        r = s * 0.62 * i / 26
        a = int(26 * (1 - i / 26) ** 1.4)
        gd.ellipse([s * .5 - r, s * .57 - r * .9, s * .5 + r, s * .57 + r * .9],
                   fill=(255, 180, 122, a))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(s * .02)))

    cx, cy = s * .5, s * .57
    rx, ry = s * .295, s * .275
    box = [cx - rx, cy - ry, cx + rx, cy + ry]

    # feet
    d.ellipse([cx - rx * .54, cy + ry * .84, cx - rx * .1, cy + ry * 1.02], fill=(233, 190, 154, 255))
    d.ellipse([cx + rx * .1, cy + ry * .84, cx + rx * .54, cy + ry * 1.02], fill=(233, 190, 154, 255))

    # antenna
    d.line([(cx, cy - ry * .9), (cx + rx * .04, cy - ry * 1.18), (cx + rx * .1, cy - ry * 1.26)],
           fill=(228, 182, 146, 255), width=max(2, int(s * .012)))
    tip = (cx + rx * .1, cy - ry * 1.26)
    d.ellipse([tip[0] - s * .03, tip[1] - s * .03, tip[0] + s * .03, tip[1] + s * .03],
              fill=(255, 180, 122, 255))

    # body: vertical gradient masked by the egg shape
    grad = Image.new("RGB", (1, s))
    for y in range(s):
        t = y / float(s)
        if t < .45:
            c = lerp((255, 249, 240), (255, 232, 210), t / .45)
        else:
            c = lerp((255, 232, 210), (244, 193, 158), (t - .45) / .55)
        grad.putpixel((0, y), c)
    grad = grad.resize((s, s))

    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse(box, fill=255)
    img.paste(grad, (0, 0), mask)

    # specular highlight
    hilite = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hilite)
    hd.ellipse([cx - rx * .6, cy - ry * .66, cx - rx * .26, cy - ry * .5],
               fill=(255, 255, 255, 110))
    img.alpha_composite(hilite.filter(ImageFilter.GaussianBlur(s * .03)))

    # blush, softened
    blush = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    bd = ImageDraw.Draw(blush)
    for side in (-1, 1):
        bx = cx + side * rx * .58
        bd.ellipse([bx - rx * .18, cy + ry * .08, bx + rx * .18, cy + ry * .3],
                   fill=(255, 116, 126, 255))
    blush = blush.filter(ImageFilter.GaussianBlur(s * .028))
    a = blush.split()[3].point(lambda v: int(v * .5))
    blush.putalpha(a)
    img.alpha_composite(blush)

    # eyes
    for side in (-1, 1):
        ex = cx + side * rx * .37
        ey = cy - ry * .08
        er = rx * .2
        d.ellipse([ex - er, ey - er * 1.1, ex + er, ey + er * 1.1], fill=(34, 29, 46, 255))
        d.ellipse([ex - er * .46, ey - er * .64, ex + er * .0, ey - er * .2],
                  fill=(255, 255, 255, 245))

    # smile
    d.arc([cx - rx * .3, cy + ry * .17, cx + rx * .3, cy + ry * .53], 24, 156,
          fill=(58, 42, 56, 255), width=max(3, int(s * .021)))

    return img.resize((px, px), Image.LANCZOS)


def main():
    for name, px in SIZES.items():
        out = RES / f"mipmap-{name}" / "ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        draw_icon(px).save(out)
        print(out.relative_to(ROOT))


if __name__ == "__main__":
    main()
