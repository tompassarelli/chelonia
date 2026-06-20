#!/usr/bin/env python3
"""Generate the authoring-harness report charts as hand-rolled SVG (no deps).
Data is the COMMITTED measured figures (LEDGER.md). Run: python3 gen-charts.py
Outputs: charts/*.svg"""
import os
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "charts")
os.makedirs(OUT, exist_ok=True)

W, H = 720, 460
ML, MR, MT, MB = 78, 28, 56, 96          # margins (big bottom for caption)
PX0, PX1 = ML, W - MR
PY0, PY1 = MT, H - MB
INK, GRID, EXEC, SUB, LSP, GRAPH = "#222", "#d8d8d8", "#e08a3c", "#2e9e5b", "#3b6fb0", "#e08a3c"

def esc(s): return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
def txt(x, y, s, sz=13, anc="start", fill=INK, wt="normal", it="normal"):
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-family="Helvetica,Arial,sans-serif" '
            f'font-size="{sz}" text-anchor="{anc}" fill="{fill}" font-weight="{wt}" '
            f'font-style="{it}">{esc(s)}</text>')
def line(x1, y1, x2, y2, c=INK, w=1, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="{c}" stroke-width="{w}"{d}/>'
def rect(x, y, w, h, fill, stroke="none"):
    return f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" fill="{fill}" stroke="{stroke}"/>'
def poly(pts, c, w=2.5):
    p = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
    return f'<polyline points="{p}" fill="none" stroke="{c}" stroke-width="{w}"/>'
def circ(cx, cy, r, fill):
    return f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r}" fill="{fill}"/>'

def frame(title, subtitle, caption):
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
         rect(0, 0, W, H, "#ffffff"),
         txt(ML, 26, title, 16, "start", INK, "bold"),
         txt(ML, 44, subtitle, 12, "start", "#666", "normal", "italic")]
    # caption (wrapped) under the plot
    cy = H - MB + 40
    import textwrap
    for i, ln in enumerate(textwrap.wrap(caption, 96)):
        s.append(txt(ML, cy + i * 15, ln, 11, "start", "#444"))
    return s

def yaxis(s, ymax, label, ticks):
    s.append(line(PX0, PY0, PX0, PY1, INK, 1.2))
    for t in ticks:
        y = PY1 - (t / ymax) * (PY1 - PY0)
        s.append(line(PX0 - 4, y, PX1, y, GRID, 1))
        s.append(line(PX0 - 4, y, PX0, y, INK, 1))
        s.append(txt(PX0 - 8, y + 4, f"{t:g}", 11, "end", "#555"))
    cy = (PY0 + PY1) / 2
    s.append(f'<text x="18" y="{cy:.1f}" transform="rotate(-90 18 {cy:.1f})" '
             f'font-family="Helvetica,Arial,sans-serif" font-size="12" text-anchor="middle" '
             f'fill="#333" font-style="italic">{esc(label)}</text>')

def legend(s, items, x, y):
    for i, (lab, col) in enumerate(items):
        yy = y + i * 18
        s.append(rect(x, yy - 9, 14, 10, col))
        s.append(txt(x + 20, yy, lab, 12))

# ---------- Chart 1: cost-vs-N, within one module (flat/flat) ----------
def chart1():
    Ns = [1, 2, 3, 4]
    lsp = [206, 207, 208, 205]; armg = [663, 707, 678, 668]
    ymax = 800
    s = frame("Rename cost vs N — within ONE module (size held constant): both arms FLAT",
              "experiments/authoring-harness/LEDGER.md · S-QUERY-CURVE · measured-with-config, warm",
              "Shows: within a fixed module (query.bclj, 10612-claim log), neither arm's rename wall-time grows "
              "with N — the pre-registered latency DIVERGENCE does not appear at small N (clojure-lsp's rename is "
              "itself ~O(1) here). Does NOT show: large N (next chart), nor that arm-G's ~3x-higher LEVEL is set by "
              "module/log SIZE, not by N.")
    yaxis(s, ymax, "rename wall-time (ms)", [0, 200, 400, 600, 800])
    s.append(line(PX0, PY1, PX1, PY1, INK, 1.2))
    def X(n): return PX0 + (n - 1) / 3 * (PX1 - PX0)
    def Y(v): return PY1 - v / ymax * (PY1 - PY0)
    for n in Ns:
        s.append(txt(X(n), PY1 + 18, f"N={n}", 11, "middle", "#555"))
    s.append(txt((PX0 + PX1) / 2, PY1 + 38, "references renamed (N)", 12, "middle", "#333", it="italic"))
    for series, col in [(lsp, LSP), (armg, EXEC)]:
        s.append(poly([(X(n), Y(v)) for n, v in zip(Ns, series)], col))
        for n, v in zip(Ns, series):
            s.append(circ(X(n), Y(v), 3.5, col))
    s.append(txt(X(4) + 4, Y(668) - 8, "arm-G edit ~668ms (flat)", 11, "end", EXEC, "bold"))
    s.append(txt(X(4) + 4, Y(205) - 8, "arm-LSP rename ~206ms (flat)", 11, "end", LSP, "bold"))
    legend(s, [("arm-LSP (clojure-lsp)", LSP), ("arm-G (graph daemon)", EXEC)], PX1 - 180, PY0 + 14)
    s.append("</svg>")
    return "\n".join(s)

# ---------- Chart 2: arm-LSP large-N (mild climb ~3.1ms/ref) ----------
def chart2():
    Ns = [9, 46, 88, 239]; ms = [2670, 2740, 2932, 3385]
    ymax = 3600
    s = frame("clojure-lsp rename on REAL honeysql — climbs MILDLY with N (~3.1 ms / ref)",
              "LEDGER.md · S-LARGE-N-LSP · measured-with-config (no port needed) · util/str N=239 (total semantic refs)",
              "Shows: lsp's rename DOES grow with N — ~3.1 ms per reference atop a ~2.64 s analysis baseline; "
              "mild and sublinear (26× N → 1.27× time, baseline-dominated). Does NOT show: arm-G at these N "
              "(honey.sql full port declined — it would confirm a conceded axis), nor any correctness gap "
              "(util/str is a STATIC symbol → clojure-lsp catches all 239, completeness holds).")
    yaxis(s, ymax, "rename wall-time (ms)", [0, 1000, 2000, 3000])
    s.append(line(PX0, PY1, PX1, PY1, INK, 1.2))
    def X(n): return PX0 + n / 250 * (PX1 - PX0)
    def Y(v): return PY1 - v / ymax * (PY1 - PY0)
    for n in Ns:
        s.append(line(X(n), PY1, X(n), PY1 + 4, INK, 1))
        s.append(txt(X(n), PY1 + 18, str(n), 11, "middle", "#555"))
    s.append(txt((PX0 + PX1) / 2, PY1 + 38, "references renamed (N)", 12, "middle", "#333", it="italic"))
    # fit line 2640 + 3.1*N
    s.append(line(X(0), Y(2640), X(250), Y(2640 + 3.1 * 250), "#bbb", 1.4, "5,4"))
    s.append(txt(X(250), Y(2640 + 3.1 * 250) - 6, "fit ≈ 2.64s + 3.1ms/ref", 11, "end", "#999"))
    s.append(poly([(X(n), Y(v)) for n, v in zip(Ns, ms)], LSP))
    for n, v in zip(Ns, ms):
        s.append(circ(X(n), Y(v), 4, LSP))
        s.append(txt(X(n), Y(v) - 9, f"{v}ms", 10, "middle", LSP))
    s.append("</svg>")
    return "\n".join(s)

# ---------- Chart 3: per-layer decomposition (execution vs substrate) ----------
def chart3():
    # (label, ms, kind) kind: sub=substrate(green), exec=execution(orange)
    rows = [("graph op (the rename itself)", 1, "sub", "<1ms — substrate"),
            ("typed-compile work (parse+check+emit)", 5, "sub", "negligible — substrate"),
            ("edit client (bb start + socket round-trip)", 336, "exec", "process startup"),
            ("render (cold rebuild of resolver from log)", 644, "exec", "→ 338ms warm via daemon :render"),
            ("recompile (beagle racket process startup)", 645, "exec", "the '5s typing tax' was COLD-CACHE; warm=645ms startup")]
    xmax = 720
    bx0 = ML + 250                      # bars start after labels
    bx1 = PX1
    s = frame("Where arm-G's rename latency LIVES — process-startup EXECUTION, not substrate",
              "LEDGER.md · S-TRACKB-DECOMP · measured warm · arm-G end-to-end (greet)",
              "Shows: arm-G's ~1.6s rename pipeline is process-startup EXECUTION (CLI+socket, cold render rebuild, "
              "beagle racket startup); the substrate graph op and the typed-compile WORK are sub-ms / negligible. "
              "Does NOT show: a meaningful substrate cost — there isn't one. (The earlier '5s recompile = typing "
              "tax' was a cold racket-cache confound, found on our own arm and retracted.)")
    n = len(rows); band = (PY1 - PY0) / n
    def Xb(v): return bx0 + v / xmax * (bx1 - bx0)
    # axis
    s.append(line(bx0, PY0, bx0, PY1, INK, 1.2))
    for t in [0, 200, 400, 600]:
        x = Xb(t)
        s.append(line(x, PY0, x, PY1, GRID, 1))
        s.append(txt(x, PY1 + 16, f"{t}", 10, "middle", "#666"))
    s.append(txt((bx0 + bx1) / 2, PY1 + 34, "milliseconds (warm)", 11, "middle", "#333", it="italic"))
    for i, (lab, ms, kind, note) in enumerate(rows):
        y = PY0 + i * band + band * 0.18
        h = band * 0.5
        col = SUB if kind == "sub" else EXEC
        w = max(Xb(ms) - bx0, 2)
        s.append(txt(bx0 - 8, y + h * 0.75, lab, 11, "end", INK))
        s.append(rect(bx0, y, w, h, col))
        lblx = bx0 + w + 6
        s.append(txt(lblx, y + h * 0.75, f"{ms}ms  ·  {note}", 10, "start", "#444"))
    legend(s, [("substrate (graph + types)", SUB), ("execution (process startup)", EXEC)], bx0, PY0 - 2)
    s.append("</svg>")
    return "\n".join(s)

# ---------- Chart 4: warm render before/after ----------
def chart4():
    bars = [("COLD\n(rebuild from log)", 644, EXEC), ("WARM\n(daemon :render)", 338, SUB)]
    ymax = 800
    s = frame("Warm render — execution win, output BYTE-IDENTICAL",
              "LEDGER.md · S-TRACKB-WARMRENDER · measured + cmp-verified · EXECUTION axis only",
              "Shows: serving render off the warm daemon (skipping the cold store boot + whole-corpus "
              "resolve-warm-store!) cuts render 644→338ms (~47%). Does NOT show: any substrate/representation "
              "change — the rendered .bclj is BYTE-IDENTICAL to the cold path (verified by cmp).")
    yaxis(s, ymax, "render wall-time (ms)", [0, 200, 400, 600, 800])
    s.append(line(PX0, PY1, PX1, PY1, INK, 1.2))
    bw = 110; gap = 120
    x = PX0 + 90
    def Y(v): return PY1 - v / ymax * (PY1 - PY0)
    for lab, v, col in bars:
        s.append(rect(x, Y(v), bw, PY1 - Y(v), col))
        s.append(txt(x + bw / 2, Y(v) - 8, f"{v}ms", 13, "middle", INK, "bold"))
        for j, ln in enumerate(lab.split("\n")):
            s.append(txt(x + bw / 2, PY1 + 18 + j * 14, ln, 11, "middle", "#555"))
        x += bw + gap
    # arrow + pct
    s.append(txt(PX0 + 90 + bw + gap / 2, Y(644) - 30, "−47%", 15, "middle", SUB, "bold"))
    s.append(txt((PX0 + PX1) / 2, Y(338) - 50, "byte-identical (cmp ✓)", 12, "middle", "#2e9e5b", "bold"))
    s.append("</svg>")
    return "\n".join(s)

for name, fn in [("cost-vs-N", chart1), ("lsp-large-N", chart2),
                 ("layer-decomposition", chart3), ("warm-render", chart4)]:
    p = os.path.join(OUT, f"{name}.svg")
    with open(p, "w") as f:
        f.write(fn())
    print("wrote", p)
