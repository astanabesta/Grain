# Validation against real reference frames

Ground truth: 77 frames published by
[Recipe Lab](https://github.com/voxivoid/recipe-lab-sony-pmca) (GPL-3.0) —
77 looks on **one scene, one exposure, one light**, straight out of a Sony A6000.
It is the only real-world reference set this project has.

## Run it

```bash
git clone --depth 1 https://github.com/voxivoid/recipe-lab-sony-pmca /tmp/rl
python3 tests/val/extract.py /tmp/rl        # measure their 77 frames
node   tests/val/render.mjs                 # render ours on their neutral frame
python3 tests/val/report.py                 # compare
```

The 77 JPEGs are **not vendored here** — they are GPL-3.0 and 4 MB. Only the
measurements (`truth.json`) and one neutral input frame (`factory.png`) are kept.

## What the report can and cannot say

**Can:** whether our looks have the right *order* and *direction* — Velvia more
saturated than Eterna, Acros exactly monochrome, and so on. That generalises,
because a look is a relative transform and sensors differ.

**Cannot:** that our colour is correct. Their pipeline is a different machine
with no tone-curve control, and their own README calls the recipes
"approximations of a look, not copies of another brand's colour science". So can
ours. No number from this report should be quoted as an accuracy figure.

## Known limits of the comparison

- One scene: no skin tone, no foliage, no sky. Portrait-leaning looks are judged
  worst by it, as Recipe Lab say themselves.
- 14 of their 77 looks have a direct equivalent in our sim list; the rest are
  Kodak/Ilford/Ricoh/Leica looks we do not claim.
- Their frames are JPEG q82 downscaled to 900 px; ours are measured on the same
  320×214 crop of the same neutral frame, so both go through one resample.
