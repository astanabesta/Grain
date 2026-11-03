#!/usr/bin/env python3
"""build.py — assemble recipe-cam-design.html

One self-contained file: the render engine, the recipe data, the sample photo and
the Fujifilm LUTs are all inlined, because a sandboxed preview iframe has no
network access. Nothing external is fetched at runtime.

    python3 design/build.py
"""
import base64
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, os.environ.get("OUT", "recipe-cam-design.html"))

# Six official sims keep the file around 1.2 MB. The other four are a one-line
# change here if you want them.
# All ten official sims. Six were embedded at first, so velvia / astia /
# proNegStd / realaAce silently fell back to the hand-built approximation.
LUTS = ["provia", "velvia", "astia", "classicchrome", "classicneg", "eterna",
        "eternabb", "pronegstd", "realaace", "acros"]


def read(path):
    with open(os.path.join(ROOT, path), "r") as fh:
        return fh.read()


def b64(path):
    with open(os.path.join(ROOT, path), "rb") as fh:
        return base64.b64encode(fh.read()).decode()


def strip_module(src):
    """Turn an ES module into something we can concatenate inside one <script>."""
    src = re.sub(r"^import\s+[^;]+;\s*$", "", src, flags=re.M)
    src = re.sub(r"^export\s+(default\s+)?", "", src, flags=re.M)
    return src


def main():
    recipes_js = strip_module(read("engine/recipes.js"))
    gl_js = strip_module(read("engine/gl.js"))
    glrender_js = strip_module(read("engine/glrender.js"))
    raw_js = strip_module(read("engine/raw.js"))
    color_js = strip_module(read("engine/color.js"))
    luts_js = strip_module(read("engine/luts.js"))

    # luts.js fetches from a URL; in the single file the data is already here.
    luts_js = luts_js.replace("const BASE = 'engine/luts/';", "const BASE = '';")
    # The UI fills this cache directly from the embedded base64 blobs.
    luts_js += "\nconst LUT_CACHE = cache;\n"

    # The community corpus: 201 real recipes. 66 KB as minified JSON, which is
    # nothing next to the LUTs, and it is what makes the app useful rather than
    # a demo with twelve looks.
    corpus = read("engine/data/recipes-corpus.json")

    lut_data = {slug: b64(f"engine/luts/{slug}.bin") for slug in LUTS}
    img_data = b64("assets/sample-scene.jpg")

    SHELL_DIR = os.environ.get("DESIGN_DIR", "design")

    shell = read(f"{SHELL_DIR}/shell.html")
    css = read(f"{SHELL_DIR}/ui.css")
    ui = read(f"{SHELL_DIR}/ui.js")

    out = shell
    out = out.replace("/*__GL__*/", gl_js)
    out = out.replace("/*__GLRENDER__*/", glrender_js)
    out = out.replace("/*__RAW__*/", raw_js)
    out = out.replace("/*__CORPUS__*/", "const RECIPES_CORPUS = " + corpus + ";")
    out = out.replace("/*__CSS__*/", css)
    out = out.replace("/*__RECIPES__*/", recipes_js)
    out = out.replace("/*__COLOR__*/", color_js)
    out = out.replace("/*__LUTS__*/", luts_js)
    out = out.replace("/*__LUTDATA__*/", json.dumps(lut_data))
    out = out.replace("__IMAGE_DATA__", img_data)
    out = out.replace("/*__UI__*/", ui)

    with open(OUT, "w") as fh:
        fh.write(out)

    size = os.path.getsize(OUT)
    print(f"wrote {os.path.relpath(OUT, ROOT)}  ({size/1024/1024:.2f} MB)")
    print(f"  inlined: engine ({len(recipes_js)+len(color_js)+len(luts_js)} chars), "
          f"{len(LUTS)} LUTs, sample image")


if __name__ == "__main__":
    main()
