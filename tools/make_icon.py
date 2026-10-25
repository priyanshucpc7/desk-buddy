#!/usr/bin/env python3
"""Generate the Android launcher icon: the same EMO-style robot as the app."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android" / "app" / "src" / "main" / "res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 8  # supersample


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def glow_ellipse(size, box, color, steps=24, strength=40):
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    rx, ry = (x1 - x0) / 2, (y1 - y0) / 2
    for i in range(steps, 0, -1):
        k = i / steps
        a = int(strength * (1 - k) ** 1.6)
        d.ellipse([cx - rx * k, cy - ry * k, cx + rx * k, cy + ry * k],
                  fill=color + (a,))
    return layer


def draw_icon(px: int) -> Image.Image:
    s = px * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # navy tile
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=s * .235, fill=(0, 0, 0, 255))

    # cyan ambient glow behind the robot
    img.alpha_composite(glow_ellipse(s, [s * .16, s * .18, s * .84, s * .86],
                                     (121, 230, 255), 26, 46)
                        .filter(ImageFilter.GaussianBlur(s * .02)))

    cx, cy = s * .5, s * .53
    hw, hh = s * .315, s * .29
    pad = hw * .14
    srad = hw * .28

    # feet
    foot_w, foot_h = hw * .34, hh * .28
    for side in (-1, 1):
        fx = cx + side * hw * .38 - foot_w / 2
        fy = cy + hh * .78
        d.rounded_rectangle([fx, fy, fx + foot_w, fy + foot_h],
                            radius=foot_w * .42, fill=(27, 38, 44, 255),
                            outline=(94, 231, 255, 110), width=max(1, int(s * .004)))
        d.rounded_rectangle([fx, fy + foot_h * .55, fx + foot_w, fy + foot_h],
                            radius=foot_w * .3, fill=(19, 28, 33, 255))

    # shell gradient
    grad = Image.new("RGB", (1, s))
    for y in range(s):
        t = y / float(s)
        if t < .55:
            c = lerp((58, 71, 80), (27, 38, 44), min(1, t / .55))
        else:
            c = lerp((27, 38, 44), (18, 26, 31), (t - .55) / .45)
        grad.putpixel((0, y), c)
    grad = grad.resize((s, s))

    shell_mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(shell_mask).rounded_rectangle(
        [cx - hw, cy - hh, cx + hw, cy + hh], radius=hw * .28, fill=255)
    img.paste(grad, (0, 0), shell_mask)

    # shell outline
    d.rounded_rectangle([cx - hw, cy - hh, cx + hw, cy + hh],
                        radius=hw * .28, outline=(94, 231, 255, 120),
                        width=max(1, int(s * .006)))

    # screen bezel + glass
    sx0, sy0 = cx - hw + pad, cy - hh + pad
    sx1, sy1 = cx + hw - pad, cy + hh - pad
    d.rounded_rectangle([sx0, sy0, sx1, sy1], radius=srad,
                        fill=(17, 23, 32, 255))
    inner = pad * .28
    d.rounded_rectangle([sx0 + inner, sy0 + inner, sx1 - inner, sy1 - inner],
                        radius=srad * .92, fill=(6, 9, 15, 255))

    # screen glow
    gs = glow_ellipse(s, [sx0 + (sx1 - sx0) * .12, sy0 + (sy1 - sy0) * .48,
                          sx1 - (sx1 - sx0) * .12, sy1 + (sy1 - sy0) * .1],
                      (121, 230, 255), 18, 60)
    screen_mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(screen_mask).rounded_rectangle(
        [sx0, sy0, sx1, sy1], radius=srad, fill=255)
    img.paste(Image.composite(Image.new("RGBA", (s, s), (0, 0, 0, 0)), gs, screen_mask),
              (0, 0), screen_mask)

    # eyes with a soft bloom
    ew, eh = hw * .34, hh * .40
    ey = cy + hh * .005
    for side in (-1, 1):
        ex = cx + side * hw * .40
        img.alpha_composite(glow_ellipse(s, [ex - ew * .8, ey - eh * .8, ex + ew * .8, ey + eh * .8],
                                         (121, 230, 255), 16, 90)
                            .filter(ImageFilter.GaussianBlur(s * .012)))
        d.rounded_rectangle([ex - ew / 2, ey - eh / 2, ex + ew / 2, ey + eh / 2],
                            radius=ew * .42, fill=(240, 254, 255, 255))
        d.rounded_rectangle([ex - ew / 2, ey + eh * .10, ex + ew / 2, ey + eh / 2],
                            radius=ew * .40, fill=(174, 239, 255, 255))

    # smile
    d.arc([cx - hw * .30, ey + eh * .55, cx + hw * .30, ey + eh * 1.35],
          22, 158, fill=(230, 253, 255, 235), width=max(2, int(s * .014)))

    # top status button + LED
    nub_w, nub_h = hw * .22, hw * .10
    d.rounded_rectangle([cx - nub_w / 2, cy - hh - nub_h * .8,
                         cx + nub_w / 2, cy - hh + nub_h * .2],
                        radius=nub_h * .45, fill=(52, 66, 76, 255),
                        outline=(94, 231, 255, 110), width=max(1, int(s * .004)))
    img.alpha_composite(glow_ellipse(s, [cx - hw * .16, cy - hh - hw * .22,
                                         cx + hw * .16, cy - hh + hw * .10],
                                     (121, 230, 255), 16, 120))
    d.ellipse([cx - hw * .038, cy - hh - hw * .07,
               cx + hw * .038, cy - hh + hw * .006],
              fill=(121, 230, 255, 255))

    return img.resize((px, px), Image.LANCZOS)


def main():
    for name, px in SIZES.items():
        out = RES / f"mipmap-{name}" / "ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        draw_icon(px).save(out)
        print(out.relative_to(ROOT))


if __name__ == "__main__":
    main()
