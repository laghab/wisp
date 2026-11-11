#!/usr/bin/env python3
"""Converts the design handoff's themes.json (CSS-valued tokens, one entry per theme with its modes) into Wisp's theme files in
app/src/main/assets/themes (one file per theme; a theme with a light and a dark look is ONE file with a "variants" block).

usage: tools/themes/from-handoff.py <path to the handoff's themes.json>
Run it again when the designs change; the files it writes are the source of truth in the app (see docs/themes.md).
"""
import json, os, re, sys

NAMES = {
    "wisp": "Wisp", "moon": "Moon Phase", "dandelion": "Dandelion", "sakura": "Sakura", "factory": "Factory", "cyberpunk": "Cyberpunk",
    "terminal": "Terminal", "y2k": "Y2K Desktop", "material": "Material You", "glass": "Liquid Glass", "modern": "Modern",
    "sakuraStorm": "Sakura Storm", "retro": "Retro", "brutalist": "Brutalist", "comic": "Comic", "manga": "Manga",
}
FONTS = {"inter": "inter", "space grotesk": "space_grotesk", "chakra petch": "chakra_petch", "orbitron": "orbitron", "vt323": "vt323",
         "bangers": "bangers", "zen maru gothic": "zen_maru", "jetbrains mono": "jetbrains_mono", "roboto": "sans", "tahoma": "sans"}

def kebab(s):
    return re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", s).lower()

def split_top(s, sep=","):
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch == "(": depth += 1
        if ch == ")": depth -= 1
        if ch == sep and depth == 0: out.append(cur.strip()); cur = ""
        else: cur += ch
    if cur.strip(): out.append(cur.strip())
    return out

def num(s): return float(re.sub(r"(px|deg|%|em)$", "", s.strip()))

def colour(c):
    c = c.strip()
    return "transparent" if c == "transparent" else c

def split_colour_stop(tok):
    """'#abc 25%' / 'rgba(1,2,3,.4) 0 25%' -> (colour, [positions as raw strings])."""
    m = re.match(r"^(#[0-9a-fA-F]+|rgba?\([^)]*\)|transparent)\s*(.*)$", tok.strip())
    if not m: raise ValueError("bad stop: " + tok)
    return m.group(1), m.group(2).split()

def stops_of(parts, length=None):
    """List of [position, colour]; positions in % -> 0..1, px -> divided by the repeat length. Two positions make a hard-edged band."""
    n = len(parts); out = []
    raw = [split_colour_stop(p) for p in parts]
    def pos(p):
        return num(p) / 100.0 if p.endswith("%") else (num(p) / length if p.endswith("px") and length else float(p) if re.match(r"^[0-9.]+$", p) and float(p) == 0 else None)
    for i, (c, ps) in enumerate(raw):
        ps = [pos(p) for p in ps]
        if not ps:
            ps = [i / (n - 1) if n > 1 else 0.0]
        for p in ps: out.append([round(p, 6), colour(c)])
    if out and out[0][0] > 0: out.insert(0, [0.0, out[0][1]])           # before the first stop the first colour holds
    if out and out[-1][0] < 1: out.append([1.0, out[-1][1]])
    return out

def layer(css):
    css = css.strip()
    if css.startswith("repeating-linear-gradient("): return gradient(css, True)
    if css.startswith("linear-gradient("): return gradient(css, False)
    if css.startswith("radial-gradient("): return radial(css)
    return colour(css)

def gradient(css, repeating):
    m = re.match(r"^(repeating-)?linear-gradient\((.*)\)\s*(.*)$", css, re.S)
    args = split_top(m.group(2)); tail = m.group(3).strip()
    angle = 180.0
    if re.match(r"^-?[0-9.]+deg$", args[0].strip()): angle = num(args[0]); args = args[1:]
    length = None
    if repeating:
        last = split_colour_stop(args[-1])[1]; length = num(last[-1]) if last and last[-1].endswith("px") else None
    elif tail and "/" in tail:               # a tiled line pattern: "linear-gradient(c 1px, transparent 1px) 0 0 / 100% 16px"
        size = tail.split("/")[1].split(); length = num(size[1] if angle in (180, 0) else size[0])
    st = stops_of(args, length if (repeating or tail) else None)
    if not repeating and tail and "/" in tail:   # px positions of the first period -> 0..1 of the period
        pass
    g = {"angle": angle, "stops": st}
    if length: g["length"] = length
    return {"linear": g}

def radial(css):
    m = re.match(r"^radial-gradient\((.*)\)\s*(.*)$", css, re.S)
    args = split_top(m.group(1)); tail = m.group(2).strip()
    first = args[0]
    if first.startswith("circle") and " at " not in first and tail and "/" in tail:       # dots: "circle, c r1, transparent r2" 0 0 / 6px 6px
        c, p1 = split_colour_stop(args[1]); _, p2 = split_colour_stop(args[2])
        step = num(tail.split("/")[1].split()[0]); return {"dots": {"step": step, "radius": round((num(p1[0]) + num(p2[0])) / 2, 2), "color": colour(c)}}
    shape = first
    stops = args[1:]
    rel = False; rx = ry = 0.0; x = y = 0.5
    mm = re.match(r"^(?:ellipse|circle)?\s*([0-9.]+(?:px|%))\s*([0-9.]+(?:px|%))?\s*at\s*([0-9.]+%?)\s+([0-9.]+%?)$", shape.strip())
    if mm:
        a, b = mm.group(1), mm.group(2) or mm.group(1)
        rel = a.endswith("%"); rx, ry = (num(a) / 100, num(b) / 100) if rel else (num(a), num(b))
        x = num(mm.group(3)) / 100; y = num(mm.group(4)) / 100
    else:
        raise ValueError("unsupported radial: " + css[:80])
    r = {"x": x, "y": y, "rx": rx, "ry": ry, "stops": stops_of(stops)}
    if rel: r["rel"] = True
    return {"radial": r}

def fill(css):
    parts = split_top(css)
    # Factory's rivets: four corner dots over a base fill
    if len(parts) == 5 and all(p.startswith("radial-gradient(circle at") for p in parts[:4]):
        c, p1 = split_colour_stop(split_top(re.match(r"^radial-gradient\((.*)\)$", parts[0]).group(1))[1])
        base = layer(parts[4])
        return [base, {"rivets": {"inset": 4.0, "radius": 1.3, "color": colour(c)}}]
    layers = [layer(p) for p in parts]
    # CSS lists the top layer first; the engine lists the bottom first
    layers = list(reversed(layers))
    return layers[0] if len(layers) == 1 else layers

def shadow_list(css):
    if css.strip() in ("none", "0", ""): return []
    out = []
    for item in split_top(css):
        inset = item.startswith("inset"); item = item.replace("inset", "", 1).strip()
        cm = re.search(r"(#[0-9a-fA-F]+|rgba?\([^)]*\))\s*$", item)
        c = cm.group(1); nums = [num(t) for t in item[:cm.start()].split()]
        sh = {"color": c}
        if inset: sh["inset"] = True
        dx, dy = nums[0], nums[1]; blur = nums[2] if len(nums) > 2 else 0.0; spread = nums[3] if len(nums) > 3 else 0.0
        if dx: sh["dx"] = dx
        if dy: sh["dy"] = dy
        if blur: sh["blur"] = blur
        if spread: sh["spread"] = spread
        out.append(sh)
    return out

def one_shadow(css):
    s = shadow_list(css)
    return s[0] if s else None

def radius(css):
    v = [num(t) for t in str(css).split()]
    return v[0] if len(v) == 1 else v

def mode_tokens(t):
    o = {}
    o["dark"] = None  # set by the caller
    o["board"] = fill(t["board"]); o["key"] = fill(t["key"]); o["mod"] = fill(t["mod"]); o["accent"] = fill(t["accent"])
    for k in ("fg", "modFg", "accentFg", "stripFg", "sep"): o[k] = t[k]
    o["radius"] = radius(t["radius"])
    if t["border"] not in ("0", 0, "none"):
        m = re.match(r"^([0-9.]+)px\s+solid\s+(.*)$", t["border"]); o["border"] = {"width": float(m.group(1)), "color": m.group(2).strip()}
    sh = shadow_list(t["shadow"]); lit = shadow_list(t["lit"])
    if sh: o["shadow"] = sh
    if lit: o["lit"] = lit
    f = t["font"].split(",")[0].strip().strip("'\"").lower()
    o["font"] = FONTS.get(f, "sans"); o["weight"] = t["weight"]; o["size"] = t["size"]
    if t["upper"]: o["upper"] = True
    tr = str(t["track"]).replace("em", "")
    if tr not in ("0", ""): o["track"] = float(tr)
    if t["clip"] != "none": o["cut"] = 8.0
    if t["blur"] != "none": o["blur"] = num(re.match(r"blur\(([0-9.]+px)", t["blur"]).group(1))
    if t["bandH"]:
        o["bandHeight"] = t["bandH"]                                   # room at the top (Sakura Storm keeps it for its branch)
        if t["band"] != "transparent": o["band"] = layer(t["band"])
    ms = one_shadow(t["micShadow"])
    if ms: o["micGlow"] = ms
    ts = one_shadow(t["textShadow"])
    if ts: o["textShadow"] = ts
    if t.get("tilt"): o["tilt"] = True
    o["space"] = t["space"]
    if t.get("fx"): o["fx"] = t["fx"]
    if t.get("art"):
        o["art"] = {"id": t["art"], "bg": fill(t["artBg"]) if t["artBg"] else None, "colors": [t["a1"], t["a2"], t["a3"]]}
        if o["art"]["bg"] is None: del o["art"]["bg"]
    return o

def main(src):
    d = json.load(open(src))
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../app/src/main/assets/themes")
    # the pictures: one SVG per theme and look, copied next to the themes (names in kebab-case, like the theme ids)
    src_dir = os.path.dirname(os.path.abspath(src))
    for sub, dest in (("space-bar-art", "art"), ("full-board-fx", "fx")):
        dd = os.path.join(out, "..", dest)
        os.makedirs(dd, exist_ok=True)
        for f in os.listdir(dd): os.remove(os.path.join(dd, f))
        for f in sorted(os.listdir(os.path.join(src_dir, sub))) if os.path.isdir(os.path.join(src_dir, sub)) else []:
            open(os.path.join(dd, kebab(f[:-4]) + ".svg"), "w").write(open(os.path.join(src_dir, sub, f)).read())
    for tid, v in d.items():
        modes = v["modes"]
        toks = {m: mode_tokens(v[m]) for m in modes}
        for m in modes: toks[m]["dark"] = (m == "dark")
        base_mode = modes[0]
        base = dict(toks[base_mode]); base["id"] = kebab(tid); base["name"] = NAMES[tid]
        if tid == "material": base["dynamic"] = True
        if len(modes) == 2:
            base["variants"] = {m: {k: val for k, val in toks[m].items() if base.get(k) != val or k == "dark"} for m in modes}
            for k in list(base["variants"][base_mode].keys()):
                if k != "dark": del base["variants"][base_mode][k]
        order = ["id", "name", "dark", "dynamic"]
        data = {k: base[k] for k in order if k in base}; data.update({k: x for k, x in base.items() if k not in data})
        with open(os.path.join(out, kebab(tid) + ".json"), "w") as f: json.dump(data, f, indent=1); f.write("\n")
        print("wrote", tid, modes)

if __name__ == "__main__":
    main(sys.argv[1])
