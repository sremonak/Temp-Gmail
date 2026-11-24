"""Generate Temp Gmail app icons (red envelope on white / solid variants)."""
from PIL import Image, ImageDraw
import os

S = 1024  # master size
RED = (217, 48, 37, 255)       # #d93025
WHITE = (255, 255, 255, 255)

def draw_envelope(d, box, bg, stroke):
    """Draw rounded-square icon with white envelope + colored flap inside box=(x0,y0,x1,y1)."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    r = int(w * 0.22)
    d.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=bg)

    # envelope body
    pad = w * 0.18
    ex0, ey0 = x0 + pad, y0 + h * 0.30
    ex1, ey1 = x1 - pad, y1 - h * 0.30
    d.rounded_rectangle([ex0, ey0, ex1, ey1], radius=w * 0.03, fill=WHITE)

    # flap (V shape) as polyline
    lw = int(w * 0.045)
    pts = [
        (ex0 + w * 0.015, ey0 + h * 0.03),
        ((ex0 + ex1) / 2, (ey0 + ey1) / 2 - h * 0.01),
        (ex1 - w * 0.015, ey0 + h * 0.03),
    ]
    d.line(pts, fill=stroke, width=lw, joint="curve")

def main():
    out_dir = os.path.join(os.path.dirname(__file__), "..", "app", "icons")
    os.makedirs(out_dir, exist_ok=True)

    # --- main launcher icon: white bg, red envelope ---
    img = Image.new("RGBA", (S, S), WHITE)
    d = ImageDraw.Draw(img)
    # red square inset with rounded corners, envelope inside
    m = int(S * 0.0)
    d.rounded_rectangle([m, m, S - m, S - m], radius=int(S * 0.18), fill=RED)
    # white envelope
    ex0, ey0, ex1, ey1 = S * 0.16, S * 0.32, S * 0.84, S * 0.68
    d.rounded_rectangle([ex0, ey0, ex1, ey1], radius=int(S * 0.035), fill=WHITE)
    lw = int(S * 0.05)
    d.line(
        [(S * 0.175, S * 0.345), (S * 0.5, S * 0.525), (S * 0.825, S * 0.345)],
        fill=RED, width=lw, joint="curve",
    )

    # full-size outputs
    img.save(os.path.join(out_dir, "icon-512.png"))
    img.resize((192, 192), Image.LANCZOS).save(os.path.join(out_dir, "icon-192.png"))
    img.resize((48, 48), Image.LANCZOS).save(os.path.join(out_dir, "favicon.png"))
    img.resize((180, 180), Image.LANCZOS).save(os.path.join(out_dir, "icon-180.png"))

    # --- Android pre-26 launcher PNGs (maskable-safe: full-bleed red) ---
    android_dir = os.path.join(os.path.dirname(__file__), "..", "android_icons")
    os.makedirs(android_dir, exist_ok=True)

    def launcher(size, round_shape=False):
        ico = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        dd = ImageDraw.Draw(ico)
        r = size // 2 if round_shape else int(size * 0.18)
        dd.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=RED)
        ex0, ey0, ex1, ey1 = size * 0.17, size * 0.33, size * 0.83, size * 0.67
        dd.rounded_rectangle([ex0, ey0, ex1, ey1], radius=max(2, int(size * 0.035)), fill=WHITE)
        dd.line(
            [(size * 0.185, size * 0.355), (size * 0.5, size * 0.525), (size * 0.815, size * 0.355)],
            fill=RED, width=max(2, int(size * 0.05)), joint="curve",
        )
        return ico

    for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d_path = os.path.join(android_dir, f"mipmap-{density}")
        os.makedirs(d_path, exist_ok=True)
        launcher(size).save(os.path.join(d_path, "ic_launcher.png"))
        launcher(size, round_shape=True).save(os.path.join(d_path, "ic_launcher_round.png"))

    print("icons written to", os.path.abspath(out_dir), "and", os.path.abspath(android_dir))

if __name__ == "__main__":
    main()
