"""Generate native Android drawable XMLs (shapes, ripples, Material vector icons)."""
import os

RES = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "res")
DRAW = os.path.join(RES, "drawable")

SHAPES = {
    "bg_circle.xml": """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/avatar_grey" />
</shape>
""",
    "bg_dot.xml": """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/gm_blue" />
</shape>
""",
    "bg_banner.xml": """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/bannerBg" />
    <corners android:radius="12dp" />
</shape>
""",
    "bg_status_pill.xml": """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/statusPillBg" />
    <corners android:radius="99dp" />
</shape>
""",
    "fg_item.xml": """<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android" android:color="#33000000">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@android:color/transparent" />
        </shape>
    </item>
</ripple>
""",
}

# Material Design icon paths (Apache-2.0)
ICONS = {
    "ic_copy": "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z",
    "ic_refresh": "M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
    "ic_add": "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    "ic_star": "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
    "ic_star_filled": "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
    "ic_delete": "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
    "ic_back": "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
    "ic_check": "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    "ic_alert": "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
    "ic_sun": "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zm0-4.5v2m0 15v2M1.5 12h2m15 0h2M4.6 4.6l1.4 1.4m12 12 1.4 1.4M19.4 4.6 18 6M6 18l-1.4 1.4M12 17a5 5 0 0 0 0-10z",
    "ic_moon": "M12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z",
    "ic_inbox_big": "M19 3H4.99c-1.11 0-1.98.89-1.98 2L3 19c0 1.1.88 2 1.99 2H19c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H4.99V5H19v10z",
    "ic_close": "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
}

VECTOR = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="{path}" />
</vector>
"""


def main():
    os.makedirs(DRAW, exist_ok=True)
    for name, content in SHAPES.items():
        with open(os.path.join(DRAW, name), "w") as f:
            f.write(content)
    for name, path in ICONS.items():
        size = "96" if name == "ic_inbox_big" else "24"
        with open(os.path.join(DRAW, name + ".xml"), "w") as f:
            f.write(VECTOR.format(size=size, path=path))

    # extra colors for pill (day + night)
    day = os.path.join(RES, "values", "colors.xml")
    night = os.path.join(RES, "values-night", "colors.xml")
    for path, pill_bg, pill_fg in [
        (day, "#E6F4EA", "#1E8E3E"),
        (night, "#1E3A26", "#6DD58C"),
    ]:
        txt = open(path).read()
        if "statusPillBg" not in txt:
            txt = txt.replace(
                "</resources>",
                f'    <color name="statusPillBg">{pill_bg}</color>\n'
                f'    <color name="statusPillFg">{pill_fg}</color>\n</resources>',
            )
            open(path, "w").write(txt)

    print("drawables + pill colors written:", len(SHAPES) + len(ICONS))


if __name__ == "__main__":
    main()
