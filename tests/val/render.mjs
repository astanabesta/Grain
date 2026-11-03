// Render our equivalent looks on the SAME scene, so the two systems can be
// compared like for like. Input is Recipe Lab's FACTORY (ST) frame — the
// camera's own neutral look — because it is the only un-looked frame in the set.
//
// This is a comparison on one scene, not a calibration: Sony's pipeline and ours
// are different machines, and Recipe Lab's own values are approximations of
// these looks rather than measurements of them.
import { chromium } from 'playwright';
import fs from 'fs';

const PAIRS = [
  // [recipe lab name, our sim, our tweaks]
  ['FACTORY (ST)',      'provia',       {}],
  ['Sony PT (portrait)','provia',       { color:-1 }],
  ['Sony NT (neutral)', 'provia',       { color:-2, highlight:-1 }],
  ['Sony VV (vivid)',   'velvia',       { color:-1 }],
  ['Provia',            'provia',       {}],
  // their Velvia is Vivid with saturation +3, and the official LUT does not
  // carry that extra push, so the recipe has to say it. Same for the others:
  // where their recipe names a Number, ours has to name it too or the
  // comparison is measuring two different intentions.
  ['Velvia',            'velvia',       { color: 3 }],
  ['Astia',             'astia',        { color: 1 }],
  ['Classic Chrome',    'classicChrome',{ color: -1, highlight: 1 }],
  ['Classic Negative',  'classicNeg',   {}],
  ['Pro Neg Std',       'proNegStd',    {}],
  ['Pro Neg Hi',        'proNegHi',     {}],
  ['Eterna',            'eterna',       { color: -2, highlight: -1, shadow: -1 }],
  ['Eterna Bleach Bypass','eternaBleachBypass', {}],
  ['Acros',             'acros',        {}],
  ['Acros +Ye',         'acros',        { monoFilter:'yellow' }],
  ['Acros +R',          'acros',        { monoFilter:'red' }],
  ['Acros +G',          'acros',        { monoFilter:'green' }],
];

const b = await chromium.launch({ args:['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'] });
const ctx = await b.newContext({ viewport:{width:600,height:400}, permissions:['camera'] });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', e => errs.push(e.message));
await p.goto('file:///home/user/recipe-cam/recipe-cam-design-v3.html');
await p.waitForTimeout(3000);

const base64 = fs.readFileSync(new URL('./factory.png', import.meta.url)).toString('base64');

const out = await p.evaluate(async ({ pairs, b64 }) => {
  const img = new Image();
  await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = 'data:image/png;base64,' + b64; });

  const W = 320, H = 214;
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const cx = c.getContext('2d', { willReadFrequently: true });
  cx.drawImage(img, 0, 0, W, H);
  const src = cx.getImageData(0, 0, W, H);

  const measure = (data) => {
    let n = 0, sl = 0, sat = 0, r = 0, g = 0, bl = 0;
    const lums = [];
    for (let i = 0; i < data.length; i += 4) {
      const R = data[i]/255, G = data[i+1]/255, B = data[i+2]/255;
      const mx = Math.max(R,G,B), mn = Math.min(R,G,B);
      sat += mx > 1e-6 ? (mx-mn)/mx : 0;
      const L = 0.2126*R + 0.7152*G + 0.0722*B;
      lums.push(L); sl += L; r += R; g += G; bl += B; n++;
    }
    const mean = sl/n;
    const contrast = Math.sqrt(lums.reduce((a,v)=>a+(v-mean)**2,0)/n);
    return {
      sat: +(sat/n).toFixed(5), luma: +mean.toFixed(5), contrast: +contrast.toFixed(5),
      rgb: [+(r/n).toFixed(5), +(g/n).toFixed(5), +(bl/n).toFixed(5)],
    };
  };

  const results = [];
  for (const [label, sim, tweaks] of pairs) {
    if (!FILM_SIMS[sim]) throw new Error('no such sim: ' + sim + ' (for ' + label + ')');
    const recipe = {
      sim, wb: 'auto', wbShiftRed: 0, wbShiftBlue: 0,
      highlight: 0, shadow: 0, color: 0, dynamicRange: 100, ev: 0,
      clarity: 0, sharpness: 0, grain: 'off', colorChrome: 'off',
      colorChromeBlue: 'off', monoFilter: 'none', ...tweaks,
    };
    const dst = cx.createImageData(W, H);
    render(src, dst, recipe, { sceneKelvin: 5500, exposure: 0, grainSeed: 0, lut: loadedSync(recipe.sim) });
    results.push({ label, sim, measured: measure(dst.data) });
  }
  return results;
}, { pairs: PAIRS, b64: base64 });

console.log(JSON.stringify({ results: out, errors: errs.length ? errs.slice(0,2) : 'none' }, null, 1));
await b.close();
