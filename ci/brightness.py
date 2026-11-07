"""Print the average brightness (0-255) of the screen's left margin, from a raw `screencap`.

Usage: adb exec-out screencap | python3 brightness.py
Samples a column 20 px from the left edge, between 30% and 60% of the height, where every
screen shows its plain background.
"""
import struct
import sys

data = sys.stdin.buffer.read()
w, h = struct.unpack_from("<II", data, 0)
header = len(data) - w * h * 4  # 12 bytes, or 16 on newer Android (adds a colour space)
total = n = 0
for y in range(h * 30 // 100, h * 60 // 100, 10):
    i = header + (y * w + 20) * 4
    r, g, b = data[i], data[i + 1], data[i + 2]
    total += (r + g + b) / 3
    n += 1
print(int(total / n))
