"""Print the centre "x y" of the on-screen element whose text (or description) matches.

Matching ignores case; an exact match wins over a partial one.
Usage: find_text.py <uiautomator dump> <text> [first|last|bounds|exact|exact-bounds|smallest]
("bounds" prints "x1 y1 x2 y2" of the first match instead; "exact" ignores partial matches;
"smallest" prints the centre of the smallest exact match, e.g. a label inside a bigger row.)
"""
import re
import sys
import xml.etree.ElementTree as ET

path, needle = sys.argv[1], sys.argv[2].lower()
which = sys.argv[3] if len(sys.argv) > 3 else "first"
nodes = list(ET.parse(path).iter("node"))


def labels(n):
    return [(n.get("text") or "").lower(), (n.get("content-desc") or "").lower()]


matches = [n for n in nodes if needle in labels(n)]
if not matches and not which.startswith("exact") and which != "smallest":
    matches = [n for n in nodes if any(needle in l for l in labels(n) if l)]
if not matches:
    sys.exit(f"'{sys.argv[2]}' is not on screen")
def area(n):
    a1, b1, a2, b2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (a2 - a1) * (b2 - b1)


if which == "smallest":
    node = min(matches, key=area)
else:
    node = matches[-1] if which == "last" else matches[0]
x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
if which.endswith("bounds"):
    print(x1, y1, x2, y2)
else:
    print((x1 + x2) // 2, (y1 + y2) // 2)
