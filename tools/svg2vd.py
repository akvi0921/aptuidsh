#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
svg2vd.py — excalidraw SVG → Android VectorDrawable 转换器
============================================================
处理范围(本项目素材实测):
  - path 元素(d 属性, 支持 M/L/H/V/C/S/Q/T/A/Z 及小写相对命令)
  - g 元素的 transform(translate/rotate/scale/matrix, 累积到 path 坐标)
  - stroke / stroke-width / stroke-opacity / fill / fill-opacity /
    stroke-linecap / stroke-linejoin
  - 忽略 defs/style/mask(仅字体声明与空遮罩)与 symbol/image/use
    (10.svg 内嵌的新建会话图标在此剥离, 由 UI 层用 4.svg 图标叠加补位)
输出: Android VectorDrawable XML(坐标 %g 精度, 保留 viewBox 尺寸)
用法: python3 svg2vd.py <in.svg> <out.xml>
"""
import math
import re
import sys
import xml.etree.ElementTree as ET

NS = "http://www.w3.org/2000/svg"


def L(tag):
    return tag if tag.startswith("{") else "{%s}%s" % (NS, tag)


# ---------------- transform 矩阵(row-major 3x3) ----------------

def mat_mul(a, b):
    return [
        a[0] * b[0] + a[1] * b[3] + a[2] * b[6],
        a[0] * b[1] + a[1] * b[4] + a[2] * b[7],
        a[0] * b[2] + a[1] * b[5] + a[2] * b[8],
        a[3] * b[0] + a[4] * b[3] + a[5] * b[6],
        a[3] * b[1] + a[4] * b[4] + a[5] * b[7],
        a[3] * b[2] + a[4] * b[5] + a[5] * b[8],
        a[6] * b[0] + a[7] * b[3] + a[8] * b[6],
        a[6] * b[1] + a[7] * b[4] + a[8] * b[7],
        a[6] * b[2] + a[7] * b[5] + a[8] * b[8],
    ]


def parse_transform(ts):
    m = [1, 0, 0, 0, 1, 0, 0, 0, 1]
    if not ts:
        return m
    for kind, args in re.findall(r"(\w+)\s*\(([^)]*)\)", ts):
        vals = [float(x) for x in re.split(r"[\s,]+", args.strip()) if x]
        if kind == "translate":
            tx = vals[0]
            ty = vals[1] if len(vals) > 1 else 0
            m = mat_mul(m, [1, 0, 0, 0, 1, 0, tx, ty, 1])
        elif kind == "rotate":
            a = math.radians(vals[0])
            cx = vals[1] if len(vals) > 1 else 0
            cy = vals[2] if len(vals) > 1 else 0
            r = [math.cos(a), math.sin(a), 0, -math.sin(a), math.cos(a), 0, 0, 0, 1]
            t1 = [1, 0, 0, 0, 1, 0, -cx, -cy, 1]
            t2 = [1, 0, 0, 0, 1, 0, cx, cy, 1]
            m = mat_mul(m, mat_mul(t1, mat_mul(r, t2)))
        elif kind == "scale":
            sx = vals[0]
            sy = vals[1] if len(vals) > 1 else sx
            m = mat_mul(m, [sx, 0, 0, 0, sy, 0, 0, 0, 1])
        elif kind == "matrix":
            m = mat_mul(m, vals)
    return m


def apply_m(m, x, y):
    return (m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])


# ---------------- path 解析与变换 ----------------

CMD_RE = re.compile(r"([MLHVCSQTAZmlhvcsqtaz])")
NUM_RE = re.compile(r"-?\d*\.?\d+(?:[eE][+-]?\d+)?")


def parse_path(d):
    parts = CMD_RE.split(d)
    out = []
    cur = None
    for p in parts:
        if not p:
            continue
        if re.fullmatch(r"[MLHVCSQTAZmlhvcsqtaz]", p):
            cur = p
        else:
            nums = [float(x) for x in NUM_RE.findall(p)]
            if nums and cur:
                out.append((cur, nums))
    return out


def transform_path(segs, m):
    x = y = sx = sy = px = py = 0.0
    res = []

    def emit(cmd, pts):
        res.append(cmd)
        res.append(" ".join("%.4g" % v for v in pts))

    for cmd, args in segs:
        c = cmd.upper()
        n = len(args)
        if c == "M":
            i = 0
            first = True
            while i + 1 < n:
                ax, ay = args[i], args[i + 1]
                if cmd.islower():
                    ax += x
                    ay += y
                nx, ny = apply_m(m, ax, ay)
                emit("M" if first else "L", [nx, ny])
                first = False
                x, y = nx, ny
                sx, sy = x, y
                px, py = x, y
                i += 2
        elif c == "L":
            i = 0
            while i + 1 < n:
                ax, ay = args[i], args[i + 1]
                if cmd.islower():
                    ax += x
                    ay += y
                nx, ny = apply_m(m, ax, ay)
                emit("L", [nx, ny])
                x, y, px, py = nx, ny, nx, ny
                i += 2
        elif c == "H":
            i = 0
            while i < n:
                ax = args[i]
                if cmd.islower():
                    ax += x
                nx, ny = apply_m(m, ax, y)
                emit("L", [nx, ny])
                x, y, px, py = nx, ny, nx, ny
                i += 1
        elif c == "V":
            i = 0
            while i < n:
                ay = args[i]
                if cmd.islower():
                    ay += y
                nx, ny = apply_m(m, x, ay)
                emit("L", [nx, ny])
                x, y, px, py = nx, ny, nx, ny
                i += 1
        elif c == "C":
            i = 0
            while i + 5 < n:
                x1, y1, x2, y2, x3, y3 = args[i:i + 6]
                if cmd.islower():
                    x1 += x; y1 += y; x2 += x; y2 += y; x3 += x; y3 += y
                pts = apply_m(m, x1, y1) + apply_m(m, x2, y2) + apply_m(m, x3, y3)
                emit("C", pts)
                x, y = pts[4], pts[5]
                px, py = pts[2], pts[3]
                i += 6
        elif c == "S":
            i = 0
            while i + 3 < n:
                x2, y2, x3, y3 = args[i:i + 4]
                if cmd.islower():
                    x2 += x; y2 += y; x3 += x; y3 += y
                x1 = 2 * x - px
                y1 = 2 * y - py
                pts = apply_m(m, x1, y1) + apply_m(m, x2, y2) + apply_m(m, x3, y3)
                emit("C", pts)
                x, y = pts[4], pts[5]
                px, py = pts[2], pts[3]
                i += 4
        elif c == "Q":
            i = 0
            while i + 3 < n:
                x1, y1, x2, y2 = args[i:i + 4]
                if cmd.islower():
                    x1 += x; y1 += y; x2 += x; y2 += y
                pts = apply_m(m, x1, y1) + apply_m(m, x2, y2)
                emit("Q", pts)
                x, y = pts[2], pts[3]
                px, py = pts[0], pts[1]
                i += 4
        elif c == "T":
            i = 0
            while i + 1 < n:
                x2, y2 = args[i:i + 2]
                if cmd.islower():
                    x2 += x; y2 += y
                x1 = 2 * x - px
                y1 = 2 * y - py
                pts = apply_m(m, x1, y1) + apply_m(m, x2, y2)
                emit("Q", pts)
                x, y = pts[2], pts[3]
                px, py = pts[0], pts[1]
                i += 2
        elif c == "A":
            i = 0
            while i + 6 < n:
                rx, ry, rot, laf, sf, x2, y2 = args[i:i + 7]
                if cmd.islower():
                    x2 += x; y2 += y
                nx, ny = apply_m(m, x2, y2)
                emit("A", [rx, ry, rot, laf, sf, nx, ny])
                x, y, px, py = nx, ny, nx, ny
                i += 7
        elif c == "Z":
            emit("Z", [])
            x, y, px, py = sx, sy, sx, sy
    return " ".join(res)


# ---------------- path 元素 XML ----------------

def color_to_argb(c):
    c = (c or "").strip()
    if c.startswith("#"):
        h = c[1:]
        if len(h) == 3:
            h = "".join(ch * 2 for ch in h)
        if len(h) == 6:
            return "#FF" + h.upper()
        if len(h) == 8:
            return "#" + h.upper()
    return None


def build_path(pd, stroke, sw, fill, fo, so, lc, lj):
    attrs = ['android:pathData="%s"' % pd]
    if fill is None or fill == "none":
        attrs.append('android:fillColor="#00000000"')
    else:
        col = color_to_argb(fill) or "#FF000000"
        attrs.append('android:fillColor="%s"' % col)
        if fo:
            attrs.append('android:fillAlpha="%s"' % fo)
    if stroke and stroke != "none":
        col = color_to_argb(stroke) or "#FF000000"
        attrs.append('android:strokeColor="%s"' % col)
        if sw:
            attrs.append('android:strokeWidth="%s"' % sw)
        if so:
            attrs.append('android:strokeAlpha="%s"' % so)
    if lc:
        attrs.append('android:strokeLineCap="%s"' % lc)
    if lj:
        attrs.append('android:strokeLineJoin="%s"' % lj)
    return "    <path " + " ".join(attrs) + "/>"


def convert(svg_path, out_path):
    tree = ET.parse(svg_path)
    root = tree.getroot()
    vb = (root.get("viewBox") or "0 0 50 50").split()
    vw, vh = float(vb[2]), float(vb[3])
    paths = []

    def walk(elem, m):
        for ch in elem:
            tag = ch.tag
            if tag == L("g"):
                walk(ch, mat_mul(m, parse_transform(ch.get("transform"))))
            elif tag == L("path"):
                d = ch.get("d")
                if not d:
                    continue
                segs = parse_path(d)
                pd = transform_path(segs, m)
                paths.append(
                    build_path(
                        pd,
                        ch.get("stroke"),
                        ch.get("stroke-width"),
                        ch.get("fill"),
                        ch.get("fill-opacity"),
                        ch.get("stroke-opacity"),
                        ch.get("stroke-linecap"),
                        ch.get("stroke-linejoin"),
                    )
                )
            # defs/style/mask/symbol/image/use/metadata: 忽略

    walk(root, [1, 0, 0, 0, 1, 0, 0, 0, 1])
    xml_lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="%gdp" android:height="%gdp"' % (vw, vh),
        '    android:viewportWidth="%g" android:viewportHeight="%g">' % (vw, vh),
    ]
    xml_lines.extend(paths)
    xml_lines.append("</vector>")
    xml_lines.append("")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(xml_lines))
    print("OK  %s → %s  (%d paths, viewBox %g×%g)" % (svg_path, out_path, len(paths), vw, vh))


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2])
