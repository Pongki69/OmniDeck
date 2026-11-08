"""Generates the Windows and Android app icons from Branding/icon.png.

Usage: python Branding/apply_icon.py
If icon.png is missing, a plain placeholder is used so the apps can still be built.
Needs Pillow.
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageStat

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "Branding" / "icon.png"
HOST_ASSETS = ROOT / "HostServer" / "Assets"
ANDROID_RES = ROOT / "MobileClient" / "app" / "src" / "main" / "res"

ADAPTIVE_CANVAS = 432
# Android masks the 108dp canvas to about 72dp. With the tile's rim cropped off (ART_INSET),
# 58dp keeps the artwork inside even a circular mask.
ADAPTIVE_ART = round(ADAPTIVE_CANVAS * 58 / 108)
ART_INSET = 0.06  # removes the straight part of the tile's bright rim


def placeholder() -> Image.Image:
    size = 1024
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=170, fill=(10, 30, 88, 255), outline=(39, 211, 245, 255), width=24)
    try:
        font = ImageFont.truetype("segoeuib.ttf", 420)
    except OSError:
        font = ImageFont.load_default()
    draw.text((size / 2, size / 2), "OD", font=font, fill=(39, 211, 245, 255), anchor="mm")
    return image


def load_tile() -> Image.Image:
    """Returns the rounded tile, cropped square, with anything outside it removed."""
    if not SOURCE.exists():
        print("Branding/icon.png not found, using the placeholder")
        return placeholder()

    image = Image.open(SOURCE).convert("RGBA")
    # The artwork sits on black or transparency. Find the tile by what is neither.
    background = Image.new("RGBA", image.size, (0, 0, 0, 255))
    flattened = Image.alpha_composite(background, image).convert("L")
    mask = flattened.point(lambda v: 255 if v > 40 else 0)
    box = mask.getbbox() or (0, 0, *image.size)

    side = max(box[2] - box[0], box[3] - box[1])
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    half = side / 2
    tile = image.crop((round(cx - half), round(cy - half), round(cx + half), round(cy + half)))

    rounded = Image.new("L", tile.size, 0)
    ImageDraw.Draw(rounded).rounded_rectangle((0, 0, tile.width - 1, tile.height - 1), radius=round(side * 0.165), fill=255)
    tile.putalpha(ImageChops.multiply(tile.getchannel("A"), rounded))
    return tile


def write_windows(tile: Image.Image) -> None:
    HOST_ASSETS.mkdir(parents=True, exist_ok=True)
    tile.resize((256, 256), Image.LANCZOS).save(HOST_ASSETS / "omnideck.png")
    sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    tile.resize((256, 256), Image.LANCZOS).save(HOST_ASSETS / "omnideck.ico", sizes=sizes)


def write_android(tile: Image.Image) -> None:
    inset = round(tile.width * ART_INSET)
    art = tile.crop((inset, inset, tile.width - inset, tile.height - inset)).resize((ADAPTIVE_ART, ADAPTIVE_ART), Image.LANCZOS)

    # The background is the median colour along the top and bottom edges: the tile's real navy,
    # unaffected by bright artwork that touches the edge.
    strip = round(ADAPTIVE_ART * 0.04)
    edge = Image.new("RGB", (ADAPTIVE_ART, strip * 2))
    edge.paste(art.crop((0, 0, ADAPTIVE_ART, strip)).convert("RGB"), (0, 0))
    edge.paste(art.crop((0, ADAPTIVE_ART - strip, ADAPTIVE_ART, ADAPTIVE_ART)).convert("RGB"), (0, strip))
    r, g, b = ImageStat.Stat(edge).median

    # A rounded mask with a slight feather removes the rim's curved corners and softens the seam.
    feather = max(2, round(ADAPTIVE_ART * 0.012))
    fade = Image.new("L", art.size, 0)
    ImageDraw.Draw(fade).rounded_rectangle(
        (feather, feather, art.width - feather, art.height - feather), radius=round(art.width * 0.24), fill=255)
    art.putalpha(fade.filter(ImageFilter.GaussianBlur(feather)))

    foreground = Image.new("RGBA", (ADAPTIVE_CANVAS, ADAPTIVE_CANVAS), (0, 0, 0, 0))
    offset = (ADAPTIVE_CANVAS - ADAPTIVE_ART) // 2
    foreground.paste(art, (offset, offset), art)

    drawable = ANDROID_RES / "drawable-nodpi"
    anydpi = ANDROID_RES / "mipmap-anydpi-v26"
    values = ANDROID_RES / "values"
    for folder in (drawable, anydpi, values):
        folder.mkdir(parents=True, exist_ok=True)

    foreground.save(drawable / "ic_launcher_foreground.png")
    (values / "ic_launcher_colors.xml").write_text(
        f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#{r:02X}{g:02X}{b:02X}</color>\n</resources>\n',
        encoding="utf-8",
    )
    (anydpi / "ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        "</adaptive-icon>\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    artwork = load_tile()
    write_windows(artwork)
    write_android(artwork)
    print("Icons written")
