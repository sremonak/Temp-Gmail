"""Customize Android res: launcher foreground, mipmaps, splash."""
from PIL import Image, ImageDraw
import os, glob, shutil

ROOT = os.path.join(os.path.dirname(__file__), "..")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
RED = (217, 48, 37, 255)
WHITE = (255, 255, 255, 255)


def envelope_foreground(size):
    """White envelope on transparent canvas (adaptive icon foreground)."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # envelope body: centered, ~52% width, ~34% height
    x0, y0 = size * 0.24, size * 0.33
    x1, y1 = size * 0.76, size * 0.67
    d.rounded_rectangle([x0, y0, x1, y1], radius=size * 0.035, fill=WHITE)
    lw = max(2, int(size * 0.05))
    # flap drawn in transparent (cut) — instead draw red flap lines over white
    d.line(
        [(size * 0.255, size * 0.352), (size * 0.5, size * 0.525), (size * 0.745, size * 0.352)],
        fill=RED, width=lw, joint="curve",
    )
    return img


def launcher_square(size, round_shape=False):
    """Full-bleed legacy icon: red rounded square + white envelope."""
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


def main():
    densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for dens, mult in densities.items():
        d = os.path.join(RES, f"mipmap-{dens}")
        if not os.path.isdir(d):
            continue
        base = 108 * mult  # legacy launcher: 48dp base
        legacy = int(48 * mult)
        envelope_foreground(int(108 * mult)).save(os.path.join(d, "ic_launcher_foreground.png"))
        launcher_square(legacy).save(os.path.join(d, "ic_launcher.png"))
        launcher_square(legacy, round_shape=True).save(os.path.join(d, "ic_launcher_round.png"))

    # splash icon (used by drawable/splash.xml)
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    launcher_square(512).save(os.path.join(nodpi, "splash_icon.png"))

    # splash layer-list replaces the old Capacitor PNG
    splash_png = os.path.join(RES, "drawable", "splash.png")
    if os.path.exists(splash_png):
        os.remove(splash_png)
    with open(os.path.join(RES, "drawable", "splash.xml"), "w") as f:
        f.write("""<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#F6F8FC"/>
        </shape>
    </item>
    <item android:gravity="center" android:width="176dp" android:height="176dp">
        <bitmap android:src="@drawable/splash_icon" android:gravity="fill"/>
    </item>
</layer-list>
""")

    # adaptive icon background → brand red
    with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#D93025</color>\n</resources>\n')

    print("android res customized")


if __name__ == "__main__":
    main()
