#!/usr/bin/env python3
"""Draws the taskbar thumbnail-toolbar icons (Windows) used by pc/main.js.

   python3 tools/make-thumbar-icons.py

   pc/assets/thumbar/{prev,play,pause,next}.png (+ @2x)        white glyphs with a dark rim - dark taskbar
                                                               (and the fallback: readable on both)
   pc/assets/thumbar/light/{prev,play,pause,next}.png (+ @2x)  dark glyphs - light taskbar (Windows light mode)

Drawn on a large canvas and scaled down for clean edges.
"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'pc', 'assets', 'thumbar')
S = 256  # drawing canvas (16 px icon = 1/16)


def mask(name):
    m = Image.new('L', (S, S), 0)
    d = ImageDraw.Draw(m)
    if name == 'play':
        d.polygon([(76, 44), (76, 212), (208, 128)], fill=255)
    elif name == 'pause':
        d.rounded_rectangle([64, 48, 110, 208], 10, fill=255)
        d.rounded_rectangle([146, 48, 192, 208], 10, fill=255)
    elif name == 'prev':
        d.rounded_rectangle([48, 52, 82, 204], 8, fill=255)
        d.polygon([(208, 52), (208, 204), (90, 128)], fill=255)
    elif name == 'next':
        d.rounded_rectangle([174, 52, 208, 204], 8, fill=255)
        d.polygon([(48, 52), (48, 204), (166, 128)], fill=255)
    return m


def solid(m, rgb):
    im = Image.new('RGBA', (S, S), rgb + (0,))
    im.putalpha(m)
    return im


def rimmed(m, fill, rim, width):
    outer = m.filter(ImageFilter.MaxFilter(width * 2 + 1))
    base = solid(outer, rim[:3])
    base.putalpha(outer.point(lambda v: v * rim[3] // 255))
    base.alpha_composite(solid(m, fill))
    return base


def save(big, folder, name):
    os.makedirs(folder, exist_ok=True)
    big.resize((16, 16), Image.LANCZOS).save(os.path.join(folder, f'{name}.png'))
    big.resize((32, 32), Image.LANCZOS).save(os.path.join(folder, f'{name}@2x.png'))


def main():
    for name in ('prev', 'play', 'pause', 'next'):
        m = mask(name)
        save(rimmed(m, (255, 255, 255), (24, 24, 24, 200), 14), OUT, name)
        save(solid(m, (32, 32, 32)), os.path.join(OUT, 'light'), name)
    print('wrote', os.path.normpath(OUT))


if __name__ == '__main__':
    main()
