// raw.js — read camera raw files and hand the render pipeline real scene data.
//
// The pipeline the app promises is RAW in -> recipe -> JPEG out. A JPEG in means
// the camera already baked its own tone curve, film sim and white balance into
// the pixels, and a recipe applied on top of that is a LUT stack, not a recipe.
//
// What this decodes, without any dependency and without the network:
//   · TIFF / DNG with uncompressed 8- or 16-bit samples (RGB or CFA)
//   · CFA data gets a bilinear demosaic; 16-bit keeps its headroom
//
// What it does NOT decode, and says so rather than producing garbage:
//   · DNGs whose image is lossless-JPEG compressed (most phone/converter DNGs)
//   · Fujifilm .RAF (proprietary, and its embedded preview is a JPEG)
// The honest answer for those is "export a 16-bit TIFF from your converter".
//
// Output is Float32Array RGB in 0..1 *scene-referred display* space: linear,
// white-balanced only to the camera's own multipliers, with the black level
// removed. The recipe does everything else, which is the whole point.

const TAGS = {
  ImageWidth: 256, ImageLength: 257, BitsPerSample: 258, Compression: 259,
  Photometric: 262, StripOffsets: 273, SamplesPerPixel: 277, RowsPerStrip: 278,
  StripByteCounts: 279, PlanarConfig: 284, Predictor: 317, ColorMatrix1: 50721,
  AsShotNeutral: 50728, BlackLevel: 50714, WhiteLevel: 50717, CFAPattern: 33422,
  CFARepeatPatternDim: 33421, DNGVersion: 50706, SubIFDs: 330, Orientation: 274,
  NewSubfileType: 254,
};

function readIFD(view, offset, little) {
  const entries = {};
  const n = view.getUint16(offset, little);
  for (let i = 0; i < n; i++) {
    const e = offset + 2 + i * 12;
    const tag = view.getUint16(e, little);
    const type = view.getUint16(e + 2, little);
    const count = view.getUint32(e + 4, little);
    const sizes = { 1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8 };
    const size = (sizes[type] || 1) * count;
    let valueAt = e + 8;
    if (size > 4) valueAt = view.getUint32(e + 8, little);
    const vals = [];
    for (let k = 0; k < count; k++) {
      if (type === 3) vals.push(view.getUint16(valueAt + k * 2, little));
      else if (type === 4) vals.push(view.getUint32(valueAt + k * 4, little));
      else if (type === 1 || type === 7) vals.push(view.getUint8(valueAt + k));
      else if (type === 5) {
        vals.push(view.getUint32(valueAt + k * 8, little) / Math.max(1, view.getUint32(valueAt + k * 8 + 4, little)));
      } else if (type === 2) {
        vals.push(String.fromCharCode(view.getUint8(valueAt + k)));
      }
    }
    entries[tag] = { type, count, vals, valueAt };
  }
  return entries;
}

function findIFDs(view) {
  const out = [];
  const bo = view.getUint16(0, false);
  let little;
  if (bo === 0x4949) little = true;
  else if (bo === 0x4d4d) little = false;
  else throw new Error('not a TIFF');
  if (view.getUint16(2, little) !== 42) throw new Error('not a TIFF');
  let off = view.getUint32(4, little);
  let guard = 0;
  while (off && guard++ < 8) {
    const ifd = readIFD(view, off, little);
    out.push(ifd);
    // SubIFDs often hold the full-size raw while IFD0 holds a preview
    if (ifd[TAGS.SubIFDs]) {
      for (const s of ifd[TAGS.SubIFDs].vals) {
        try { out.push(readIFD(view, s, little)); } catch (e) { /* skip */ }
      }
    }
    off = view.getUint32(off + 2 + view.getUint16(off, little) * 12, little);
  }
  return { ifds: out, little };
}

function hasTag(ifd, t) { return Object.prototype.hasOwnProperty.call(ifd, t); }

/** Pick the IFD that actually carries pixel data, preferring the big one. */
function pickImageIFD(ifds) {
  let best = null;
  for (const ifd of ifds) {
    const w = ifd[TAGS.ImageWidth]?.vals[0];
    const h = ifd[TAGS.ImageLength]?.vals[0];
    if (!w || !h || !hasTag(ifd, TAGS.StripOffsets)) continue;
    if (!best || w * h > best.w * best.h) best = { ifd, w, h };
  }
  return best;
}

function samplePixels(view, ifd, w, h, spp, bits, little) {
  const offsets = ifd[TAGS.StripOffsets].vals;
  const counts = ifd[TAGS.StripByteCounts]?.vals || [];
  const out = new Float32Array(w * h * spp);
  let p = 0;
  const maxVal = Math.pow(2, bits) - 1;
  for (let s = 0; s < offsets.length; s++) {
    const start = offsets[s];
    const bytes = counts[s] || (w * spp * h * bits / 8);
    if (bits === 16) {
      const n = Math.min(bytes, w * h * spp * 2 - p * 2) / 2;
      for (let i = 0; i < n; i++) out[p++] = view.getUint16(start + i * 2, little) / maxVal;
    } else if (bits === 8) {
      const n = Math.min(bytes, w * h * spp - p);
      for (let i = 0; i < n; i++) out[p++] = view.getUint8(start + i) / maxVal;
    } else {
      throw new Error(`${bits}-bit packed samples are not supported`);
    }
  }
  return out;
}

/** Bilinear demosaic of a CFA plane. Slow, obvious, and good enough to prove
 *  the pipeline — not a replacement for a proper raw processor. */
function demosaicCFA(cfa, w, h, pattern) {
  // pattern like [0,1,1,2] = RGGB
  const at = (x, y) => cfa[Math.min(h - 1, Math.max(0, y)) * w + Math.min(w - 1, Math.max(0, x))];
  const colorAt = (x, y) => pattern[(y % 2) * 2 + (x % 2)];
  const out = new Float32Array(w * h * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const c = colorAt(x, y);
      const o = (y * w + x) * 3;
      let r, g, b;
      if (c === 0) {                       // R site
        r = at(x, y);
        g = (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1)) / 4;
        b = (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) / 4;
      } else if (c === 2) {                // B site
        b = at(x, y);
        g = (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1)) / 4;
        r = (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) / 4;
      } else {                             // G site
        g = at(x, y);
        if (colorAt(x - 1, y) === 0 || colorAt(x + 1, y) === 0) {
          r = (at(x - 1, y) + at(x + 1, y)) / 2;
          b = (at(x, y - 1) + at(x, y + 1)) / 2;
        } else {
          r = (at(x, y - 1) + at(x, y + 1)) / 2;
          b = (at(x - 1, y) + at(x + 1, y)) / 2;
        }
      }
      out[o] = r; out[o + 1] = g; out[o + 2] = b;
    }
  }
  return out;
}

/** Convert a DNG CFA pattern tag to [tl,tr,bl,br] colour indices. */
function parseCFAPattern(ifd) {
  const dim = ifd[TAGS.CFARepeatPatternDim]?.vals || [2, 2];
  const p = ifd[TAGS.CFAPattern]?.vals || [0, 1, 1, 2];
  if (dim[0] === 2 && dim[1] === 2 && p.length >= 4) return p.slice(0, 4);
  return [0, 1, 1, 2];
}

/**
 * Decode a raw file. Returns { rgb, width, height, meta, note } where rgb is
 * Float32Array in 0..1, or throws with a message that can be shown to the user.
 */
export function decodeRaw(buffer) {
  const view = new DataView(buffer);
  if (buffer.byteLength < 8) throw new Error('File is too small to be an image');

  const { ifds, little } = findIFDs(view);
  const picked = pickImageIFD(ifds);
  if (!picked) throw new Error('No pixel data found (unsupported or truncated file)');
  const { ifd, w, h } = picked;

  const bits = ifd[TAGS.BitsPerSample]?.vals[0] || 8;
  const spp = ifd[TAGS.SamplesPerPixel]?.vals[0] || 1;
  const compression = ifd[TAGS.Compression]?.vals[0] || 1;
  if (compression !== 1) {
    throw new Error(`This file uses compression ${compression} (usually lossless JPEG). ` +
      'Export a 16-bit TIFF from your raw converter instead.');
  }

  let rgb;
  const meta = { width: w, height: h, bits, isRaw: false, wb: null };

  if (spp >= 3) {
    const px = samplePixels(view, ifd, w, h, spp, bits, little);
    rgb = new Float32Array(w * h * 3);
    for (let i = 0, j = 0; i < w * h; i++, j += spp) {
      rgb[i * 3] = px[j]; rgb[i * 3 + 1] = px[j + 1]; rgb[i * 3 + 2] = px[j + 2];
    }
  } else {
    // CFA — this is the real raw path
    const px = samplePixels(view, ifd, w, h, 1, bits, little);
    // samplePixels already normalised to 0..1 by (2^bits - 1), so the levels
    // have to be normalised the same way. Doing it twice — which shipped for one
    // revision — multiplied every pixel by 65536 and produced a white rectangle.
    const full = Math.pow(2, bits) - 1;
    const black = (ifd[TAGS.BlackLevel]?.vals[0] || 0) / full;
    const white = (ifd[TAGS.WhiteLevel]?.vals[0] || full) / full;
    const span = Math.max(1e-3, white - black);
    for (let i = 0; i < px.length; i++) px[i] = Math.max(0, Math.min(1, (px[i] - black) / span));

    // AsShotNeutral -> per-channel multipliers. This is the *camera's* balance,
    // nothing more: the recipe's white balance still has to run on top.
    const neutral = ifd[TAGS.AsShotNeutral]?.vals;
    let mul = [1, 1, 1];
    if (neutral && neutral.length >= 3 && neutral[0] > 0 && neutral[2] > 0) {
      const g = neutral[1] || 1;
      // Normalise so the largest multiplier is 1: a raw multiplier above 1
      // would clip the channel it is meant to correct.
      let m = [g / neutral[0], 1, g / neutral[2]].map((v) => (Number.isFinite(v) && v > 0 ? v : 1));
      const top = Math.max(...m);
      m = m.map((v) => +(v / top).toFixed(4));
      mul = m;
      meta.wb = mul.map((v) => +v.toFixed(3));
    }
    rgb = demosaicCFA(px, w, h, parseCFAPattern(ifd));
    for (let i = 0; i < rgb.length; i += 3) {
      rgb[i] *= mul[0]; rgb[i + 1] *= mul[1]; rgb[i + 2] *= mul[2];
    }
    meta.isRaw = true;
  }

  meta.note = meta.isRaw
    ? `${bits}-bit CFA · demosaiced · ${w}×${h}`
    : `${bits}-bit TIFF · ${w}×${h}`;
  return { rgb, width: w, height: h, meta };
}

/** Reduce to 8-bit sRGB for the CPU engine, which works in display space. */
export function toImageData(decoded, maxDim = 1600) {
  const { rgb, width: w0, height: h0 } = decoded;
  const scale = Math.min(1, maxDim / Math.max(w0, h0));
  const w = Math.max(1, Math.round(w0 * scale));
  const h = Math.max(1, Math.round(h0 * scale));
  const out = new Uint8ClampedArray(w * h * 4);
  const enc = (v) => {
    const c = Math.max(0, Math.min(1, v));
    const s = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    return Math.round(s * 255);
  };
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const sx = Math.min(w0 - 1, Math.round(x / scale));
      const sy = Math.min(h0 - 1, Math.round(y / scale));
      const si = (sy * w0 + sx) * 3;
      const di = (y * w + x) * 4;
      out[di] = enc(rgb[si]);
      out[di + 1] = enc(rgb[si + 1]);
      out[di + 2] = enc(rgb[si + 2]);
      out[di + 3] = 255;
    }
  }
  return { data: out, width: w, height: h };
}
