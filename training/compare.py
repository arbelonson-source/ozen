import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
runs = [json.load(open(f"{HERE}/results/{l}.summary.json")) for l in sys.argv[1:]]
keys = [k for k in runs[0] if k not in ("label", "model", "items", "seconds")]
print(f"{'':30s}" + "".join(f"{r['label']:>18s}" for r in runs))
for k in keys:
    base = runs[0][k]
    cells = []
    for r in runs:
        v = r.get(k, "")
        d = f" ({v - base:+.1f})" if r is not runs[0] and isinstance(v, (int, float)) else ""
        cells.append(f"{v}{d}".rjust(18))
    print(f"{k:30s}" + "".join(cells))
