"""QA 图像对比工具（T3）：
1) diff 两张截图，输出变化像素/bbox（验证"动画在动"）
2) pngalpha 统计指定矩形内纯黑/透明棋盘格比例（验证"无黑底/透明"）
用法：
  python imgdiff.py diff a.png b.png
  python imgdiff.py region a.png x0 y0 x1 y1
"""
import json
import sys

import numpy as np
from PIL import Image


def diff(a_path, b_path):
    a = Image.open(a_path).convert("RGB")
    b = Image.open(b_path).convert("RGB")
    if a.size != b.size:
        print(json.dumps({"error": "size mismatch", "a": a.size, "b": b.size}))
        return
    arr = np.abs(np.asarray(a, dtype=np.int16) - np.asarray(b, dtype=np.int16)).sum(axis=2)
    changed = int((arr > 12).sum())
    total = int(arr.size)
    ys, xs = np.where(arr > 12)
    bbox = None
    if len(xs):
        bbox = [int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1]
    print(json.dumps({
        "changed_px": changed,
        "total_px": total,
        "ratio": round(changed / total, 5),
        "changed_bbox": bbox,
        "mean_abs_diff": round(float(arr.mean()), 3),
    }, ensure_ascii=False))


def region(path, x0, y0, x1, y1):
    im = Image.open(path).convert("RGB")
    crop = np.asarray(im.crop((x0, y0, x1, y1)), dtype=np.uint8)
    r, g, b = crop[:, :, 0].astype(int), crop[:, :, 1].astype(int), crop[:, :, 2].astype(int)
    total = int(r.size)
    black = int(((r < 12) & (g < 12) & (b < 12)).sum())
    magenta = int(((r > 240) & (g < 20) & (b > 240)).sum())
    cyan = int(((r < 20) & (g > 240) & (b > 240)).sum())
    print(json.dumps({
        "rect": [x0, y0, x1, y1],
        "total": total,
        "black": black,
        "black_ratio": round(black / total, 4),
        "magenta": magenta,
        "cyan": cyan,
        "checker_ratio": round((magenta + cyan) / total, 4),
    }, ensure_ascii=False))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "diff" and len(sys.argv) == 4:
        diff(sys.argv[2], sys.argv[3])
    elif cmd == "region" and len(sys.argv) == 7:
        region(sys.argv[2], *[int(v) for v in sys.argv[3:7]])
    else:
        print(__doc__)
        sys.exit(1)
