#!/usr/bin/env python3
"""Prints a markdown table of PIT line coverage and mutation score per top-level package.

Usage: pit-summary.py [REPORT_DIR]   (default build/reports/pitest, the HTML report written by ./gradlew pitest)
Reads the per-package rows of REPORT_DIR/index.html, so it needs no dependencies.
"""
import collections
import re
import sys

report = sys.argv[1] if len(sys.argv) > 1 else "build/reports/pitest"
html = open(report + "/index.html", encoding="utf-8").read()

# <a href="./PKG/index.html"> ... classes, line cov (n/m), mutation cov (n/m), test strength (n/m)
row = re.compile(
    r'<td><a href="\./([^/]+)/index\.html">[^<]+</a></td>\s*<td>\d+</td>\s*'
    r'<td>.*?(\d+)/(\d+)</div></div></td>\s*<td>.*?(\d+)/(\d+)</div></div></td>\s*<td>.*?(\d+)/(\d+)</div>',
    re.S)

totals = collections.OrderedDict()
for name, lc, lt, mk, mt, sk, st in row.findall(html):
    parts = name.split(".")
    top = parts[2] if len(parts) > 2 else name
    for key in (top, "total"):
        acc = totals.setdefault(key, [0] * 6)
        for i, v in enumerate((lc, lt, mk, mt, sk, st)):
            acc[i] += int(v)


def pct(a, b):
    return "n/a" if b == 0 else "%.1f%%" % (100.0 * a / b)


print("### PIT mutation testing\n")
print("| package | line coverage | mutation score | test strength |")
print("|---|---|---|---|")
order = sorted(k for k in totals if k != "total") + ["total"]
for key in order:
    lc, lt, mk, mt, sk, st = totals[key]
    print("| %s | %s (%d/%d) | %s (%d/%d) | %s |" % (
        key, pct(lc, lt), lc, lt, pct(mk, mt), mk, mt, pct(sk, st)))
