#!/usr/bin/env python3
"""Draws the taskbar thumbnail-toolbar icons (Windows) used by pc/main.js.

   python3 tools/make-thumbar-icons.py      -> pc/assets/thumbar/{prev,play,pause,next}.png (+ @2x)

White glyphs on a transparent background, drawn large and scaled down for clean edges.
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'pc', 'assets', 'thumbar')
S = 256  # drawing canvas


def glyph(name):
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = (255, 255, 255, 255)
    if name == 'play':
        d.polygon([(72, 40), (72, 216), (212, 128)], fill=w)
    elif name == 'pause':
        d.rounded_rectangle([60, 44, 108, 212], 10, fill=w)
        d.rounded_rectangle([148, 44, 196, 212], 10, fill=w)
    elif name == 'prev':
        d.rounded_rectangle([44, 48, 80, 208], 8, fill=w)
        d.polygon([(212, 48), (212, 208), (88, 128)], fill=w)
    elif name == 'next':
        d.rounded_rectangle([176, 48, 212, 208], 8, fill=w)
        d.polygon([(44, 48), (44, 208), (168, 128)], fill=w)
    return im


def main():
    os.makedirs(OUT, exist_ok=True)
    for name in ('prev', 'play', 'pause', 'next'):
        big = glyph(name)
        big.resize((16, 16), Image.LANCZOS).save(os.path.join(OUT, f'{name}.png'))
        big.resize((32, 32), Image.LANCZOS).save(os.path.join(OUT, f'{name}@2x.png'))
    print('wrote', os.path.normpath(OUT))


if __name__ == '__main__':
    main()
