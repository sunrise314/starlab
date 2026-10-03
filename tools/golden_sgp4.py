# -*- coding: utf-8 -*-
"""用 skyfield（完整 SGP4 实现）为自研 SGP4-lite 生成黄金数据。

方法论：以 ISS 真实 TLE 为输入，skyfield 输出 ITRS（ECEF）位置作为真值，
落盘 JSON 供 Sgp4GoldenDataTest 断言，让"物理上基本正确"成为 CI 里的断言。

用法:
  pip install skyfield
  python tools/golden_sgp4.py tools/iss.tle src/test/resources/golden/iss-sgp4.json
"""
import json
import sys

import numpy as np
from skyfield.api import EarthSatellite, load
from skyfield.framelib import itrs


def main(tle_path, out_path):
    with open(tle_path, encoding="utf-8") as f:
        lines = [ln.strip() for ln in f if ln.strip()]
    name, line1, line2 = lines[0], lines[1], lines[2]

    ts = load.timescale()
    sat = EarthSatellite(line1, line2, name, ts)

    # 历元起 0~24h，每 1h 一个采样点（25 点）
    import datetime as dt
    hours = list(range(25))
    base = sat.epoch.utc_datetime().replace(microsecond=0)
    times = ts.from_datetimes([base + dt.timedelta(hours=h) for h in hours])

    pos = sat.at(times).frame_xyz(itrs).km  # (3, N) ITRS/ECEF，km
    points = []
    for i, h in enumerate(hours):
        at = base + dt.timedelta(hours=h)
        points.append({
            "hours": h,
            "t": at.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "x": float(pos[0, i]), "y": float(pos[1, i]), "z": float(pos[2, i]),
        })

    doc = {
        "name": name, "line1": line1, "line2": line2,
        "epoch": base.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "skyfield EarthSatellite.frame_xyz(itrs), 1h x 25 points",
        "points": points,
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    print(f"golden data written: {out_path} ({len(points)} points, epoch {doc['epoch']})")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
