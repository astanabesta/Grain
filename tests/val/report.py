#!/usr/bin/env python3
"""Compare our looks against Recipe Lab's reference frames, and say what it means.

    python3 tests/val/report.py

Two questions, deliberately separated because they have different answers:

1. SAME SCENE, SAME INPUT — our render on their neutral frame vs their frame for
   the same look name. Any agreement here is agreement about *intent*.

2. POPULATION DIRECTION — across all 77 of their frames and the matching
   measurement of ours, does our pipeline put the looks in the same order, with
   the same spread? This is the one that generalises: a Sony sensor and an
   iPhone sensor have different colour, but a look is a *relative* transform.

What this cannot do: prove our colour is correct. Sony's pipeline, no tone-curve
control, and their own README calls these "approximations of a look, not copies
of another brand's colour science". So can ours.
"""
import json
from scipy.stats import spearmanr

import os
d = os.path.dirname(os.path.abspath(__file__))
truth = json.load(open(os.path.join(d, 'truth.json')))
ours = json.load(open(os.path.join(d, 'ours.json')))['results']
by_label = {r['label']: r['measured'] for r in ours}
by_name = {t['name']: t for t in truth}

EPS = 1e-9

def row(label):
    return by_label.get(label), by_name.get(label)

print('=' * 74)
print('1 · SAME SCENE, SAME INPUT  (our render vs their frame for the same look)')
print('=' * 74)
print('Their frames are straight out of a Sony A6000; ours are the official Fujifilm')
print('LUT on top of their neutral frame. Agreement = we caught the intent.\n')
print(f"{'look':24s} {'sat theirs':>10} {'sat ours':>9} {'Δ':>7} {'luma Δ':>8}  verdict")
agree, worse = 0, []
for label in by_label:
    o, t = row(label)
    if not t: continue
    ds = o['sat'] - t['sat']
    dl = o['luma'] - t['luma']
    # a look is "captured in direction" if we are on the same side of neutral
    same_side = (ds >= 0) == (t['sat'] - 0.126 >= 0) if abs(t['sat'] - 0.126) > 0.02 else True
    ok = abs(ds) < 0.10
    agree += ok
    if not ok: worse.append((label, ds))
    print(f'{label:24s} {t["sat"]:10.3f} {o["sat"]:9.3f} {ds:+7.3f} {dl:+8.3f}  '
          + ('same ballpark' if ok else 'off by ' + f'{abs(ds):.2f}'))
print(f'\n{agree}/{len([l for l in by_label if l in by_name])} within 0.10 saturation of their frame')

print()
print('=' * 74)
print('2 · POPULATION DIRECTION  (does our pipeline rank looks the same way?)')
print('=' * 74)
common = [l for l in by_label if l in by_name]
xs = [by_name[l]['sat'] for l in common]
ys = [by_label[l]['sat'] for l in common]
rho, p = spearmanr(xs, ys)
print(f'looks compared: {len(common)}')
print(f'Spearman rank correlation of saturation: rho = {rho:+.3f}  (p = {p:.4f})')
print(f'their saturation spread: {min(xs):.3f} … {max(xs):.3f}  (range {max(xs)-min(xs):.3f})')
print(f'ours:                    {min(ys):.3f} … {max(ys):.3f}  (range {max(ys)-min(ys):.3f})')

# monotone reference looks only: the ones where a direction is not in question
print('\nknown-order checks (a look that MUST be more saturated than another):')
# (higher, lower, why) — these are not opinions, they are what the names mean
checks = [
    ('Velvia', 'Provia',   'Velvia is the vivid sim, Provia the reference'),
    ('Pro Neg Hi', 'Eterna', 'Eterna is the muted cinema sim'),
    ('Velvia', 'Eterna',   'opposite ends of the range'),
    ('Provia', 'Acros',    'Acros is monochrome'),
    ('Velvia', 'Acros',    'Acros is monochrome'),
]
for hi, lo, why in checks:
    if hi in by_label and lo in by_label:
        a, b = by_label[hi]['sat'], by_label[lo]['sat']
        ok = a > b
        print(f'  {hi:12s} > {lo:12s}  {"PASS" if ok else "FAIL"}  ({why})')
        print(f'    ours: {a:.3f} vs {b:.3f}')

mono = [l for l in common if by_label[l]['sat'] < 1e-6]
their_mono = [l for l in common if by_name[l]['sat'] < 1e-6]
print(f'\nmonochrome looks — theirs: {len(their_mono)}, ours: {len(mono)}')
print(f'  we desaturate: {sorted(mono)}')
print(f'  they desaturate: {sorted(their_mono)}')

print()
print('=' * 74)
print('VERDICT')
print('=' * 74)
print('This file supports statements about ORDER and DIRECTION only. It does not')
print('measure colour accuracy, and no number here should be quoted as if it did.')
