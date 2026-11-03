"""Mini 'NewApi' lint: lists framework API references whose 'since' level is above minSdk."""
import re, subprocess, sys, glob, os
import xml.etree.ElementTree as ET
API_XML, CLASSES, MIN = sys.argv[1], sys.argv[2], int(sys.argv[3])
root = ET.parse(API_XML).getroot()
cls_since, mem_since, parents = {}, {}, {}
for c in root.findall("class"):
    n = c.get("name"); cs = int(c.get("since", "1")); cls_since[n] = cs
    parents[n] = [e.get("name") for e in c if e.tag in ("extends", "implements")]
    for m in c:
        if m.tag in ("method", "field"):
            key = m.get("name")
            if m.tag == "method": key = key  # name(desc)ret
            mem_since[(n, key)] = int(m.get("since", str(cs)))
def lookup(owner, key, seen=None):
    seen = seen or set()
    if owner in seen: return None
    seen.add(owner)
    if (owner, key) in mem_since: return mem_since[(owner, key)]
    for p in parents.get(owner, []):
        r = lookup(p, key, seen)
        if r is not None: return r
    return None
files = glob.glob(os.path.join(CLASSES, "**", "*.class"), recursive=True)
out = subprocess.run(["javap", "-c", "-p"] + files, capture_output=True, text=True).stdout
cur_cls = cur_m = None
hits = {}
for line in out.splitlines():
    m = re.match(r"^(?:public |final |abstract |class |interface |enum |private |protected |static )*(?:class|interface|enum) ([\w.$]+)", line)
    if m and not line.startswith(" "): cur_cls = m.group(1); continue
    m = re.match(r"^  \S.*?([\w$<>]+)\(.*\).*;$", line)
    if m: cur_m = m.group(1); continue
    m = re.search(r"// (Method|InterfaceMethod|Field|class) ([\w/$]+)(?:\.([\w$<>\"]+):(\S+))?", line)
    if not m: continue
    kind, owner, name, desc = m.groups()
    if not (owner.startswith("android/") or owner.startswith("java/")): continue
    if kind == "class":
        s = cls_since.get(owner)
        what = owner
    else:
        name = name.strip('"')
        if kind == "Field": key = name
        else:
            mm = re.match(r"\((.*)\)(.*)", desc); key = f"{name}({mm.group(1)}){mm.group(2)}"
        s = lookup(owner, key)
        if s is None: s = cls_since.get(owner)
        what = f"{owner}.{name}"
    if s is not None and s > MIN:
        hits.setdefault((cur_cls, cur_m), set()).add(f"{what} (API {s})")
for (c, m), v in sorted(hits.items(), key=lambda x: (str(x[0][0]), str(x[0][1]))):
    print(f"{c}.{m}:")
    for x in sorted(v): print("    ", x)
