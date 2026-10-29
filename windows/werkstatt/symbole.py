"""Wabensymbole mit Pillow: Werkstatt-Symbol (Infobereich, EXE) und Symbole für Web-Werkzeuge."""
from __future__ import annotations

import math
from pathlib import Path
from typing import Optional

from PIL import Image, ImageDraw, ImageFont

ANTHRAZIT = (19, 19, 18, 255)
CREME = (237, 231, 218, 255)
ROT = (229, 64, 42, 255)
GROESSEN = [(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (128, 128), (256, 256)]


def wabe(cx: float, cy: float, r: float) -> list:
    return [(cx + r * math.cos(math.radians(-90 + 60 * i)), cy + r * math.sin(math.radians(-90 + 60 * i))) for i in range(6)]


def _grund(n: int) -> Image.Image:
    bild = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(bild).rounded_rectangle([0, 0, n - 1, n - 1], radius=n * 0.22, fill=ANTHRAZIT)
    return bild


def werkstatt(n: int = 256) -> Image.Image:
    """Drei Waben: oben rot, darunter zwei cremefarbene – wie auf dem Handy."""
    s = 4
    N = n * s
    bild = _grund(N)
    d = ImageDraw.Draw(bild)
    r = N * 0.2
    abstand = math.sqrt(3) * r + N * 0.035
    dy = abstand * math.sqrt(3) / 2
    c = N / 2
    y0 = c - 2 * dy / 3 + N * 0.02
    d.polygon(wabe(c, y0, r), fill=ROT)
    d.polygon(wabe(c - abstand / 2, y0 + dy, r), fill=CREME)
    d.polygon(wabe(c + abstand / 2, y0 + dy, r), fill=CREME)
    return bild.resize((n, n), Image.LANCZOS)


def _schrift(groesse: int, schriftdatei: Optional[Path]):
    for p in ([schriftdatei] if schriftdatei else []) + [Path("C:/Windows/Fonts/segoeuib.ttf")]:
        try:
            if p and Path(p).exists():
                return ImageFont.truetype(str(p), groesse)
        except OSError:
            continue
    return ImageFont.load_default(size=groesse) if hasattr(ImageFont, "load_default") else None


def web_werkzeug(name: str, n: int = 256, schriftdatei: Optional[Path] = None) -> Image.Image:
    """Wabe als Umriss mit Anfangsbuchstaben."""
    s = 4
    N = n * s
    bild = _grund(N)
    d = ImageDraw.Draw(bild)
    d.polygon(wabe(N / 2, N / 2, N * 0.33), outline=CREME, width=max(2, int(N * 0.045)))
    zeichen = (name.strip()[:1] or "?").upper()
    f = _schrift(int(N * 0.3), schriftdatei)
    d.text((N / 2, N / 2 + N * 0.01), zeichen, fill=CREME, font=f, anchor="mm")
    return bild.resize((n, n), Image.LANCZOS)


def als_ico(bild: Image.Image, ziel: Path) -> Path:
    ziel.parent.mkdir(parents=True, exist_ok=True)
    bild.save(ziel, format="ICO", sizes=GROESSEN)
    return ziel
