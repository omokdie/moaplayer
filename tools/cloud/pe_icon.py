"""Replace the icon images of a PE executable in place (no section resizing).
Each RT_ICON slot gets a PNG image no larger than the original slot; RT_GROUP_ICON entries are updated."""
import struct, sys, io
from PIL import Image

def rd(fmt, b, o): return struct.unpack_from(fmt, b, o)

def parse(buf):
    pe = rd("<I", buf, 0x3C)[0]
    assert buf[pe:pe+4] == b"PE\0\0"
    nsec = rd("<H", buf, pe + 6)[0]
    optsz = rd("<H", buf, pe + 20)[0]
    opt = pe + 24
    magic = rd("<H", buf, opt)[0]
    dd = opt + (112 if magic == 0x20B else 96)
    rsrc_rva, rsrc_size = rd("<II", buf, dd + 2 * 8)
    secs = []
    so = opt + optsz
    for i in range(nsec):
        name, vsize, va, rawsz, rawptr = rd("<8sIIII", buf, so + i * 40)
        secs.append((name.rstrip(b"\0"), va, vsize, rawptr, rawsz))
    def off(rva):
        for n, va, vs, rp, rs in secs:
            if va <= rva < va + max(vs, rs): return rva - va + rp
        raise ValueError("rva not mapped")
    base = off(rsrc_rva)
    def walk(dir_off, depth, path, out):
        named, ids = rd("<HH", buf, base + dir_off + 12)
        for i in range(named + ids):
            name, ptr = rd("<II", buf, base + dir_off + 16 + i * 8)
            key = name & 0x7FFFFFFF if not (name & 0x80000000) else ("name", name & 0x7FFFFFFF)
            if ptr & 0x80000000: walk(ptr & 0x7FFFFFFF, depth + 1, path + [key], out)
            else:
                ent = base + ptr
                data_rva, size = rd("<II", buf, ent)
                out.append((path + [key], ent, data_rva, size))
    out = []
    walk(0, 0, [], out)
    return out, off

def main(exe, png, dst):
    buf = bytearray(open(exe, "rb").read())
    entries, off = parse(buf)
    icons = {e[0][1]: e for e in entries if e[0][0] == 3}       # RT_ICON id -> entry
    groups = [e for e in entries if e[0][0] == 14]              # RT_GROUP_ICON
    src = Image.open(png).convert("RGBA")
    print("icons:", len(icons), "groups:", len(groups))
    for gpath, gent, grva, gsize in groups:
        go = off(grva)
        reserved, typ, count = rd("<HHH", buf, go)
        for k in range(count):
            eo = go + 6 + k * 14
            w, h, colors, res, planes, bits, nbytes, icon_id = rd("<BBBBHHIH", buf, eo)
            size = w or 256
            path, ent, rva, slot = icons[icon_id]
            img = src.resize((size, size), Image.LANCZOS)
            bio = io.BytesIO(); img.save(bio, "PNG", optimize=True); data = bio.getvalue()
            if len(data) > slot:
                # fall back to fewer colors to fit the slot
                img2 = img.quantize(colors=128).convert("RGBA")
                bio = io.BytesIO(); img2.save(bio, "PNG", optimize=True); data = bio.getvalue()
            if len(data) > slot:
                print(f"  skip {size}px: {len(data)} > slot {slot}"); continue
            o = off(rva)
            buf[o:o + slot] = data + b"\0" * (slot - len(data))
            struct.pack_into("<I", buf, ent + 4, len(data))                  # resource data size
            struct.pack_into("<BBBBHHIH", buf, eo, w, h, 0, 0, 1, 32, len(data), icon_id)
            print(f"  {size}px -> {len(data)} bytes (slot {slot})")
    open(dst, "wb").write(buf)

if __name__ == "__main__":
    main(*sys.argv[1:4])
