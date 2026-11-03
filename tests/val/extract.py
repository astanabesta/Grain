#!/usr/bin/env python3
"""Extract ground truth from Recipe Lab's sample frames.

Recipe Lab (voxivoid/recipe-lab-sony-pmca, GPL-3.0) publishes 77 frames: 77
different looks on ONE scene, ONE exposure, ONE light, straight out of a Sony
A6000. That makes it the only real-world reference set available to this project
without a camera in the room.

What it can and cannot tell us is important and is stated in the report:
  · it CAN tell us whether our looks have the right ORDER and DIRECTION
    (Velvia more saturated than Eterna, Acros exactly monochrome, ...)
  · it CANNOT tell us our colour is correct — a different sensor, a different
    pipeline, and no tone-curve control at all. Recipe Lab says the same of
    itself: "approximations of a look, not copies of another brand's science"

    python3 tests/val/extract.py <path-to-recipe-lab-clone>
"""
import json, os, sys
import numpy as np
from PIL import Image, ImageOps

SIZE = (320, 214)   # both systems measured on the same pixel count

def metrics(img):
    """Everything the report compares, measured identically on both sides."""
    a = img.astype(np.float64) / 255.0
    mx, mn = a.max(axis=2), a.min(axis=2)
    sat = float(np.mean((mx - mn) / np.maximum(mx, 1e-6)))
    luma = float(np.mean(0.2126*a[:,:,0] + 0.7152*a[:,:,1] + 0.0722*a[:,:,2]))
    contrast = float(np.std(0.2126*a[:,:,0] + 0.7152*a[:,:,1] + 0.0722*a[:,:,2]))
    ch = [float(a[:,:,i].mean()) for i in range(3)]
    # colour cast: how far the channel balance is from neutral grey
    grey = sum(ch)/3
    cast = [round(c - grey, 5) for c in ch]
    return {'sat': round(sat,5), 'luma': round(luma,5), 'contrast': round(contrast,5),
            'rgb': [round(c,5) for c in ch], 'cast': cast}

def main():
    root = sys.argv[1]
    truth = json.load(open(os.path.join(os.path.dirname(__file__), '..', '..', 'data', 'recipe-lab-truth.json')))
    frames = os.path.join(root, 'docs', 'samples')

    out = []
    for r in truth:
        path = os.path.join(frames, r['frame'])
        im = ImageOps.exif_transpose(Image.open(path)).convert('RGB').resize(SIZE, Image.LANCZOS)
        arr = np.asarray(im)
        m = metrics(arr)
        m.update({'n': r['n'], 'name': r['name'], 'group': r['group'],
                  'style': r['style'], 'args': r['args'], 'frame': r['frame']})
        out.append(m)

    # the neutral frame, handed to our engine as input for the same-scene test
    base = ImageOps.exif_transpose(
        Image.open(os.path.join(frames, truth[0]['frame']))).convert('RGB')
    base.save(os.path.join(os.path.dirname(__file__), 'factory.png'))

    dst = os.path.join(os.path.dirname(__file__), 'truth.json')
    json.dump(out, open(dst,'w'), indent=0)
    print(f'measured {len(out)} frames')
    print(f'  saturation range: {min(x["sat"] for x in out):.3f} … {max(x["sat"] for x in out):.3f}')
    print(f'  luma range:       {min(x["luma"] for x in out):.3f} … {max(x["luma"] for x in out):.3f}')

if __name__ == '__main__':
    main()
