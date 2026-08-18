#!/usr/bin/env bash
#
# Renders the brand SVGs into every raster format the packaging needs.
#
# The vectors in assets/ are the source; everything below is generated and none of it is committed
# by hand. Three products need three different containers:
#
#   icon.png    Linux    jpackage takes a PNG for the .deb/.rpm and the window
#   icon.ico    Windows  jpackage and the Avalonia installer both take an ICO, multi-size
#   icon.icns   macOS    the .app bundle
#   document.ico         what Explorer draws next to a .md file Quill has claimed
#   installer.ico        what the download shelf draws next to QuillSetup.exe
#
# An ICO is not one image. Windows picks the nearest size from what the file holds and rescales if it
# has to, and a 256-only ICO looks blurry at 16 px in the taskbar precisely where it is seen most.
#
# Usage: tools/render-icons.sh
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

python3 - <<'PY'
import cairosvg
from PIL import Image
import io, pathlib

ASSETS = pathlib.Path("assets")

# The sizes Windows actually asks for, smallest first so Pillow keeps them all.
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]

# The sizes an .icns is allowed to contain.
ICNS_SIZES = [16, 32, 64, 128, 256, 512, 1024]


def render(name: str, size: int) -> Image.Image:
    png = cairosvg.svg2png(url=str(ASSETS / f"{name}.svg"), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def write_png(name: str, out: pathlib.Path, size: int = 1024) -> None:
    render(name, size).save(out)
    print(f"  {out}  {size}x{size}")


def write_ico(name: str, out: pathlib.Path) -> None:
    # Each size rendered from the vector rather than downscaled from one raster: a feather's tip is
    # a few pixels wide at 16 px and survives being drawn, not being resampled.
    #
    # And where a `-small` drawing exists it is used below 32 px. An icon that works at 256 px
    # rarely works at 16: detail that reads as a plate and an arrow at full size becomes four grey
    # pixels and a smudge, and the answer is a simpler drawing rather than a smaller one.
    small = ASSETS / f"{name}-small.svg"
    frames = [
        render(f"{name}-small" if s < 32 and small.exists() else name, s)
        for s in ICO_SIZES
    ]
    frames[-1].save(out, format="ICO", sizes=[(s, s) for s in ICO_SIZES], append_images=frames[:-1])
    print(f"  {out}  {','.join(str(s) for s in ICO_SIZES)}")


def write_icns(name: str, out: pathlib.Path) -> None:
    frames = {s: render(name, s) for s in ICNS_SIZES}
    frames[1024].save(out, format="ICNS", append_images=[frames[s] for s in ICNS_SIZES if s != 1024])
    print(f"  {out}  up to 1024")


print("==> application")
write_png("icon", ASSETS / "icon.png", 512)
write_ico("icon", ASSETS / "icon.ico")
write_icns("icon", ASSETS / "icon.icns")

print("==> document type")
write_ico("file", ASSETS / "document.ico")
write_png("file", ASSETS / "document.png", 512)

print("==> installer")
write_ico("installer", ASSETS / "installer.ico")

print("==> installer project")
setup = pathlib.Path("installer-windows/Quill.Installer/Assets")
setup.mkdir(parents=True, exist_ok=True)
write_ico("installer", setup / "quill.ico")
PY
