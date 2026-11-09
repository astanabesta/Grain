package dev.grain.photo.develop;

/**
 * Single-lens portrait bokeh (natural Vivo-style shallow depth of field) for the Apple RAW looks:
 * given a quarter-resolution person mask, the background is blurred in linear light with a blur
 * disc whose radius grows smoothly with distance from the subject edge (chamfer depth ramp), like
 * a real fast lens, while the subject itself is never blurred (pixel-sharp foreground composite).
 *
 * <p>Method: sRGB-decode to linear at quarter resolution, exclusion-weighted blur pyramid (the
 * foreground never leaks into the background bokeh), continuous per-pixel disc radius pick between
 * pyramid levels with log-radius interpolation, then bilinear upsample + foreground composite and
 * sRGB re-encode. Aperture simulation maps an f-number to a max disc radius (f/0.95 is roughly
 * H/32 px at full resolution, matching what a 50mm f/1.0-style portrait looks like on this frame
 * size).
 */
public final class PortraitBokeh {
  private PortraitBokeh() {}

  private static final float[] SRGB_LIN = new float[256];

  static {
    for (int i = 0; i < 256; i++) {
      float c = i / 255f;
      SRGB_LIN[i] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }
  }

  private static float linToSrgb(float x) {
    if (x <= 0f) return 0f;
    if (x >= 1f) return 1f;
    return x <= 0.0031308f ? 12.92f * x : (float) (1.055 * Math.pow(x, 1.0 / 2.4) - 0.055);
  }

  /** Max disc radius at full resolution (px) for a simulated f-number. */
  public static float maxRadiusPx(int h, float fNumber) {
    float f = Math.max(0.7f, Math.min(22f, fNumber));
    return Math.max(2f, Math.min(0.05f * h, 0.0312f * h / (float) Math.sqrt(f)));
  }

  /**
   * Applies portrait bokeh. {@code argb}/{@code out} are full-resolution {@code w x h} pixels;
   * {@code maskQ} is the person weight (0 background .. 1 person) on the quarter-resolution grid
   * {@code qw x qh} where {@code qw = (w + 3) / 4} and {@code qh = (h + 3) / 4}. {@code depthQ} is
   * the optional monocular relative closeness (0 far .. 1 near) on the same grid; when present,
   * the disc radius is true optical defocus {@code r = rMax * f(|d - d_focus|)} with the focus
   * plane = the subject's robust median depth — so objects beside the subject at its focal plane
   * stay sharp no matter their class, and defocus grows continuously with real depth instead of
   * a 2.5D distance-from-edge ramp. Returns the disc radius used (full-res px).
   */
  public static float apply(
      int[] argb, int w, int h, float[] maskQ, int qw, int qh, float fNumber, int[] out, float[] depthQ) {
    return applyRadius(argb, w, h, maskQ, qw, qh, maxRadiusPx(h, fNumber), out, depthQ);
  }

  /** Back-compat wrapper (no depth): falls back to the chamfer distance ramp. */
  public static float apply(
      int[] argb, int w, int h, float[] maskQ, int qw, int qh, float fNumber, int[] out) {
    return applyRadius(argb, w, h, maskQ, qw, qh, maxRadiusPx(h, fNumber), out, null);
  }

  /** Same, with an explicit max disc radius in full-resolution pixels (tests use this). */
  public static float applyRadius(
      int[] argb, int w, int h, float[] maskQ, int qw, int qh, float rMaxFull, int[] out) {
    return applyRadius(argb, w, h, maskQ, qw, qh, rMaxFull, out, null);
  }

  /** Same, with an explicit max disc radius and optional relative closeness map. */
  public static float applyRadius(
      int[] argb, int w, int h, float[] maskQ, int qw, int qh, float rMaxFull, int[] out,
      float[] depthQ) {
    final int n = qw * qh;
    // Quarter-resolution linear-light downsample of the frame.
    float[] lr = new float[n], lg = new float[n], lb = new float[n];
    downsampleLinear(argb, w, h, lr, lg, lb, qw, qh);

    float[] ms = new float[n];
    boxBlur(maskQ, ms, qw, qh, 1); // smooth the matte stair-steps (~6 full-res px)
    float[] bgw = new float[n];
    for (int i = 0; i < n; i++) bgw[i] = 1f - ms[i];

    // Radius field: optical defocus from depth when a depth map is present, else the classic
    // boundary-distance ramp.
    float[] rf = new float[n];
    float rMaxQ = Math.max(1.25f, rMaxFull / 4f);
    if (depthQ == null || depthQ.length != n || !depthRadii(ms, depthQ, rf, qw, qh, rMaxQ)) {
      chamferRadii(bgw, rf, qw, qh, rMaxQ);
    }

    // Per-pixel blend weight into the bokeh layer: the foreground mask gates it, and so does the
    // radius itself — a zero-defocus pixel (subject, or same-plane object) stays pixel-sharp even
    // though it is not part of the person mask.
    float[] endw = new float[n];
    for (int i = 0; i < n; i++) endw[i] = bgw[i] * Math.min(1f, rf[i] * 1.5f);

    // Highlight boost for the defocused content only: real lenses render point highlights as
    // crisp-mean discs, not dimmed blobs. A mild linear-domain push (clamped) keeps light-points
    // visible through the discs without touching the sharp foreground.
    float[] br0 = new float[n], bg0 = new float[n], bb0 = new float[n];
    for (int i = 0; i < n; i++) {
      br0[i] = boostDisc(lr[i]);
      bg0[i] = boostDisc(lg[i]);
      bb0[i] = boostDisc(lb[i]);
    }

    // Exclusion-weighted disc stack (foreground excluded from the background's bokeh); the disc
    // content is the highlight-boosted copy, the sharp downsample stays pristine.
    float[] radii = {rMaxQ * 0.30f, rMaxQ * 0.62f, rMaxQ};
    float[][] lvlR = new float[3][], lvlG = new float[3][], lvlB = new float[3][];
    float[] pr = new float[n], pg = new float[n], pb = new float[n], pw = new float[n];
    for (int i = 0; i < n; i++) {
      pr[i] = br0[i] * bgw[i];
      pg[i] = bg0[i] * bgw[i];
      pb[i] = bb0[i] * bgw[i];
      pw[i] = bgw[i];
    }
    float prevSig = 0f;
    for (int k = 0; k < 3; k++) {
      // Cascaded box blurs add variance in quadrature; a disc of radius r has sigma = r/sqrt(2).
      float sigK = radii[k] * 0.7071f;
      float need = (float) Math.sqrt(Math.max(0.01f, sigK * sigK - prevSig * prevSig));
      int step = Math.max(1, Math.round((float) Math.sqrt(3f * need * need + 0.25f) - 0.5f));
      prevSig = sigK;
      float[] tr = new float[n], tg = new float[n], tb = new float[n], tw = new float[n];
      boxBlur(pr, tr, qw, qh, step);
      boxBlur(pg, tg, qw, qh, step);
      boxBlur(pb, tb, qw, qh, step);
      boxBlur(pw, tw, qw, qh, step);
      pr = tr;
      pg = tg;
      pb = tb;
      pw = tw;
      lvlR[k] = new float[n];
      lvlG[k] = new float[n];
      lvlB[k] = new float[n];
      for (int i = 0; i < n; i++) {
        float den = Math.max(1e-4f, pw[i]);
        lvlR[k][i] = pr[i] / den;
        lvlG[k][i] = pg[i] / den;
        lvlB[k][i] = pb[i] / den;
      }
    }

    // Per-pixel continuous-radius pick between stack levels (log-radius interpolation).
    float[] br = new float[n], bg = new float[n], bb = new float[n];
    for (int i = 0; i < n; i++) {
      float r = rf[i];
      if (r <= 0.05f || bgw[i] <= 0.001f) {
        br[i] = lr[i];
        bg[i] = lg[i];
        bb[i] = lb[i];
        continue;
      }
      int k0;
      float x0, x1;
      if (r <= radii[0]) {
        // interpolate between the sharp image and the smallest disc level
        float t = r / Math.max(0.25f, radii[0]);
        br[i] = lr[i] + t * (lvlR[0][i] - lr[i]);
        bg[i] = lg[i] + t * (lvlG[0][i] - lg[i]);
        bb[i] = lb[i] + t * (lvlB[0][i] - lb[i]);
        continue;
      } else if (r >= radii[1]) {
        k0 = 1;
        x0 = radii[1];
        x1 = radii[2];
      } else {
        k0 = 0;
        x0 = radii[0];
        x1 = radii[1];
      }
      float t = (float) Math.max(0f, Math.min(1f, Math.log(r / x0) / Math.log(x1 / x0)));
      br[i] = lvlR[k0][i] + t * (lvlR[k0 + 1][i] - lvlR[k0][i]);
      bg[i] = lvlG[k0][i] + t * (lvlG[k0 + 1][i] - lvlG[k0][i]);
      bb[i] = lvlB[k0][i] + t * (lvlB[k0 + 1][i] - lvlB[k0][i]);
    }

    // Full-resolution composite: sharp linear-light foreground over the upscaled bokeh layer.
    final float qwScale = qw / (float) w, qhScale = qh / (float) h;
    for (int y = 0; y < h; y++) {
      int o = y * w;
      float qy = (y + 0.5f) * qhScale - 0.5f;
      int y0 = (int) Math.floor(qy);
      float fy = qy - y0;
      int yc0 = y0 < 0 ? 0 : y0 >= qh - 1 ? qh - 1 : y0;
      int yc1 = yc0 >= qh - 1 ? yc0 : yc0 + 1;
      int r0 = yc0 * qw, r1 = yc1 * qw;
      float wTop = 1f - fy;
      for (int x = 0; x < w; x++) {
        float qx = (x + 0.5f) * qwScale - 0.5f;
        int x0 = (int) Math.floor(qx);
        float fx = qx - x0;
        int xc0 = x0 < 0 ? 0 : x0 >= qw - 1 ? qw - 1 : x0;
        int xc1 = xc0 >= qw - 1 ? xc0 : xc0 + 1;
        float w00 = wTop * (1f - fx), w01 = wTop * fx, w10 = fy * (1f - fx), w11 = fy * fx;
        float bw =
            endw[r0 + xc0] * w00 + endw[r0 + xc1] * w01 + endw[r1 + xc0] * w10 + endw[r1 + xc1] * w11;
        int px = argb[o + x];
        float fr = SRGB_LIN[(px >>> 16) & 255], fg = SRGB_LIN[(px >>> 8) & 255], fb = SRGB_LIN[px & 255];
        float rr, gg, bbc;
        if (bw <= 0.004f) {
          rr = fr;
          gg = fg;
          bbc = fb;
        } else {
          rr =
              br[r0 + xc0] * w00 + br[r0 + xc1] * w01 + br[r1 + xc0] * w10 + br[r1 + xc1] * w11;
          gg =
              bg[r0 + xc0] * w00 + bg[r0 + xc1] * w01 + bg[r1 + xc0] * w10 + bg[r1 + xc1] * w11;
          bbc =
              bb[r0 + xc0] * w00 + bb[r0 + xc1] * w01 + bb[r1 + xc0] * w10 + bb[r1 + xc1] * w11;
          rr = fr + bw * (rr - fr);
          gg = fg + bw * (gg - fg);
          bbc = fb + bw * (bbc - fb);
        }
        int ri = Math.round(linToSrgb(rr) * 255f);
        int gi = Math.round(linToSrgb(gg) * 255f);
        int bi = Math.round(linToSrgb(bbc) * 255f);
        out[o + x] = 0xff000000 | (ri << 16) | (gi << 8) | bi;
      }
    }
    return rMaxFull;
  }

  /** 4x4 average downsample with sRGB to linear decode. */
  private static void downsampleLinear(
      int[] argb, int w, int h, float[] lr, float[] lg, float[] lb, int qw, int qh) {
    for (int qy = 0; qy < qh; qy++) {
      int y0 = qy * 4, y1 = Math.min(h, y0 + 4);
      int o = qy * qw;
      for (int qx = 0; qx < qw; qx++) {
        int x0 = qx * 4, x1 = Math.min(w, x0 + 4);
        float r = 0, g = 0, b = 0;
        int cnt = 0;
        for (int y = y0; y < y1; y++) {
          int ro = y * w;
          for (int x = x0; x < x1; x++) {
            int px = argb[ro + x];
            r += SRGB_LIN[(px >>> 16) & 255];
            g += SRGB_LIN[(px >>> 8) & 255];
            b += SRGB_LIN[px & 255];
            cnt++;
          }
        }
        lr[o + qx] = r / cnt;
        lg[o + qx] = g / cnt;
        lb[o + qx] = b / cnt;
      }
    }
  }

  /** Chamfer (2-pass) distance into the background, converted to a saturating disc radius. */
  private static void chamferRadii(float[] bgw, float[] rf, int qw, int qh, float rMaxQ) {
    final float INF = 1e9f;
    for (int i = 0; i < qw * qh; i++) rf[i] = bgw[i] > 0.45f ? INF : 0f;
    for (int y = 0; y < qh; y++) {
      int o = y * qw;
      for (int x = 0; x < qw; x++) {
        float d = rf[o + x];
        if (d == 0f) continue;
        if (x > 0) d = Math.min(d, rf[o + x - 1] + 1f);
        if (y > 0) {
          d = Math.min(d, rf[o - qw + x] + 1f);
          if (x > 0) d = Math.min(d, rf[o - qw + x - 1] + 1.4142f);
          if (x < qw - 1) d = Math.min(d, rf[o - qw + x + 1] + 1.4142f);
        }
        rf[o + x] = d;
      }
    }
    float ramp = Math.max(2f, rMaxQ * 0.55f);
    for (int y = qh - 1; y >= 0; y--) {
      int o = y * qw;
      for (int x = qw - 1; x >= 0; x--) {
        float d = rf[o + x];
        if (d == 0f) continue;
        if (x < qw - 1) d = Math.min(d, rf[o + x + 1] + 1f);
        if (y < qh - 1) {
          d = Math.min(d, rf[o + qw + x] + 1f);
          if (x < qw - 1) d = Math.min(d, rf[o + qw + x + 1] + 1.4142f);
          if (x > 0) d = Math.min(d, rf[o + qw + x - 1] + 1.4142f);
        }
        rf[o + x] = d >= INF * 0.5f ? rMaxQ : Math.min(rMaxQ, d * rMaxQ / ramp);
      }
    }
  }

  /**
   * Resamples an upright-orientation quarter mask ({@code quW x quH}) onto the sensor-oriented
   * quarter grid of a {@code w x h} frame ({@code qw = (w + 3) / 4}, {@code qh = (h + 3) / 4}),
   * where the sensor image is displayed upright by rotating clockwise {@code uprightDegCw}
   * degrees (0, 90, 180 or 270).
   */
  public static float[] sampleUprightMaskToSensor(
      float[] maskU, int quW, int quH, int w, int h, int uprightDegCw) {
    int qw = (w + 3) / 4, qh = (h + 3) / 4;
    float[] out = new float[qw * qh];
    int deg = ((uprightDegCw % 360) + 360) % 360;
    if (deg % 90 != 0) deg = 0;
    for (int qy = 0; qy < qh; qy++) {
      float sy = qy * 4f + 2f;
      for (int qx = 0; qx < qw; qx++) {
        float sx = qx * 4f + 2f;
        float ux, uy; // sensor point rotated clockwise into the upright frame
        if (deg == 90) {
          ux = h - 1f - sy;
          uy = sx;
        } else if (deg == 270) {
          ux = sy;
          uy = w - 1f - sx;
        } else if (deg == 180) {
          ux = w - 1f - sx;
          uy = h - 1f - sy;
        } else {
          ux = sx;
          uy = sy;
        }
        float mx = ux * 0.25f - 0.5f, my = uy * 0.25f - 0.5f;
        int x0 = (int) Math.floor(mx), y0 = (int) Math.floor(my);
        float fx = mx - x0, fy = my - y0;
        int xc0 = x0 < 0 ? 0 : x0 >= quW - 1 ? quW - 1 : x0;
        int yc0 = y0 < 0 ? 0 : y0 >= quH - 1 ? quH - 1 : y0;
        int xc1 = xc0 >= quW - 1 ? xc0 : xc0 + 1;
        int yc1 = yc0 >= quH - 1 ? yc0 : yc0 + 1;
        out[qy * qw + qx] =
            maskU[yc0 * quW + xc0] * (1 - fx) * (1 - fy)
                + maskU[yc0 * quW + xc1] * fx * (1 - fy)
                + maskU[yc1 * quW + xc0] * (1 - fx) * fy
                + maskU[yc1 * quW + xc1] * fx * fy;
      }
    }
    return out;
  }

  /**
   * Hair-band anchor: lifts the matte confidence inside the hair/outline region around the
   * detected face so flyaway strands (whose matte alpha is typically 0.2-0.5 on a 480px model)
   * are never blurred. The soft elliptical floor (max blend, soft rim) covers the face box
   * expanded 1.9x tall / 1.5x wide, shifted toward the hairline. Everything outside stays the
   * model's alpha untouched. {@code faceRect} is full-res sensor coords; {@code mask}/{@code qw x
   * qh} is the sensor quarter grid.
   */
  public static void applyHairAnchor(float[] mask, int qw, int qh, int[] faceRect, int w, int h) {
    if (faceRect == null || faceRect.length < 4) return;
    float cx = (faceRect[0] + faceRect[2]) * 0.5f / 4f;
    float cy = (faceRect[1] + faceRect[3]) * 0.5f / 4f;
    float hw = Math.max(1f, (faceRect[2] - faceRect[0]) / 4f);
    float hh = Math.max(1f, (faceRect[3] - faceRect[1]) / 4f);
    // Anchor ellipse: face box + hair volume (shifted up toward the hairline).
    float ex = cx, ey = cy - 0.45f * hh, rx = hw * 1.5f, ry = hh * 1.9f;
    int x0 = Math.max(0, (int) Math.floor(ex - rx * 1.2f));
    int x1 = Math.min(qw - 1, (int) Math.ceil(ex + rx * 1.2f));
    int y0 = Math.max(0, (int) Math.floor(ey - ry * 1.2f));
    int y1 = Math.min(qh - 1, (int) Math.ceil(ey + ry * 1.2f));
    for (int y = y0; y <= y1; y++) {
      for (int x = x0; x <= x1; x++) {
        double dx = (x + 0.5 - ex) / rx, dy = (y + 0.5 - ey) / ry;
        double d = dx * dx + dy * dy;
        if (d >= 1.15) continue;
        // soft rim: full strength inside d<=1, fades to nothing at d=1.15
        float floor = d <= 1.0 ? 0.9f : (float) (0.9 * (1.15 - d) / 0.15);
        int i = y * qw + x;
        if (mask[i] < floor) mask[i] = floor;
      }
    }
  }

  /**
   * Fallback person mask when ML segmentation is unavailable: a soft-body ellipse expanded from
   * the best camera face box (face + hair + shoulders/torso silhouette), sensor oriented.
   */
  public static float[] faceEllipseMask(int[] faceRect, int w, int h) {
    int qw = (w + 3) / 4, qh = (h + 3) / 4;
    float[] m = new float[qw * qh];
    if (faceRect == null || faceRect.length < 4) return m;
    float cx = (faceRect[0] + faceRect[2]) * 0.5f / 4f;
    float cyF = (faceRect[1] + faceRect[3]) * 0.5f / 4f;
    float fw = Math.max(2f, (faceRect[2] - faceRect[0]) / 4f);
    float fh = Math.max(2f, (faceRect[3] - faceRect[1]) / 4f);
    float cy = cyF + fh * 0.55f, rx = fw * 1.15f, ry = fh * 2.6f;
    for (int y = 0; y < qh; y++)
      for (int x = 0; x < qw; x++) {
        double dx = (x + 0.5 - cx) / rx, dy = (y + 0.5 - cy) / ry;
        double d = dx * dx + dy * dy;
        m[y * qw + x] = d <= 1.0 ? 1f : 0f;
      }
    return m;
  }

  /**
   * Mild highlight boost for defocused content: keeps point lights bright enough to read as
   * bokeh discs. Linear-domain push proportional to how close a value already is to white.
   */
  private static float boostDisc(float v) {
    if (v <= 0.60f) return v;
    float t = Math.min(1f, (v - 0.60f) / 0.35f);
    t = t * t * (3f - 2f * t);
    return Math.min(1f, v * (1f + 0.45f * t));
  }

  /**
   * Optical defocus radius from a relative closeness map. The focus plane is the subject-median
   * depth; pixels farther than the subject blur with {@code r = rMax * smoothstep(delta)}, pixels
   * at the subject's depth stay sharp whatever their class (bed beside the person, held objects).
   * Returns false when the matte carries no usable subject core (caller falls back to the ramp).
   */
  public static boolean depthRadii(float[] ms, float[] depth, float[] rf, int qw, int qh, float rMaxQ) {
    int[] hist = new int[256];
    int cnt = 0;
    int n = qw * qh;
    for (int i = 0; i < n; i++)
      if (ms[i] > 0.65f) {
        hist[Math.max(0, Math.min(255, (int) (depth[i] * 255.99f)))]++;
        cnt++;
      }
    if (cnt < 24) return false;
    int acc = 0, med = 0;
    for (int i = 0; i < 256; i++) {
      acc += hist[i];
      if (acc > cnt / 2) {
        med = i;
        break;
      }
    }
    float dFocus = med / 255f;
    float minD = 1f;
    for (int i = 0; i < n; i++) if (depth[i] < minD) minD = depth[i];
    // Defocus grows with the distance DROP behind the subject; anything at-or-closer than the
    // focus plane (same-depth objects, held items, arms) stays sharp like real optics.
    float scale = Math.max(0.08f, dFocus - minD);
    for (int i = 0; i < n; i++) {
      float c = ms[i];
      float gate = c <= 0.25f ? 1f : c >= 0.65f ? 0f : (0.65f - c) / 0.4f;
      float dd = depth[i];
      float delta = dd >= dFocus ? 0f : Math.min(1f, (dFocus - dd) / scale);
      float s = delta * delta * (3f - 2f * delta);
      rf[i] = rMaxQ * s * gate;
    }
    return true;
  }

  /**
   * Letterbox content transform of a {@code uw x uh} full-resolution upright image onto an
   * {@code mw x mh} model canvas: returns {@code {fit, offsetX, offsetY}} so that the model pixel
   * of upright point (ux, uy) is {@code (ux * fit + ox, uy * fit + oy)}. Shared by all portrait
   * NN wrappers so every model sees the same mapping regardless of the bitmap's own resolution.
   */
  public static float[] letterboxContent(int uw, int uh, int mw, int mh) {
    float fit = Math.min(mw / (float) uw, mh / (float) uh);
    int cw = Math.max(1, Math.round(uw * fit));
    int ch = Math.max(1, Math.round(uh * fit));
    return new float[] {fit, (mw - cw) / 2f, (mh - ch) / 2f};
  }

  /** Separable O(1)-per-pixel box blur, edge-replicated. */
  private static void boxBlur(float[] in, float[] out, int w, int h, int rad) {
    float[] tmp = new float[w];
    int n = 2 * rad + 1;
    for (int y = 0; y < h; y++) {
      int o = y * w;
      float s = 0;
      for (int k = -rad; k <= rad; k++) s += in[o + Math.max(0, Math.min(w - 1, k))];
      for (int x = 0; x < w; x++) {
        tmp[x] = s / n;
        int a = Math.min(w - 1, x + rad + 1), m = Math.max(0, x - rad);
        s += in[o + a] - in[o + m];
      }
      // vertical accumulate: reuse a column pass below
      System.arraycopy(tmp, 0, out, o, w);
    }
    float[] col = new float[h];
    for (int x = 0; x < w; x++) {
      float s = 0;
      for (int k = -rad; k <= rad; k++) s += out[Math.max(0, Math.min(h - 1, k)) * w + x];
      for (int y = 0; y < h; y++) {
        col[y] = s / n;
        int a = Math.min(h - 1, y + rad + 1), m = Math.max(0, y - rad);
        s += out[a * w + x] - out[m * w + x];
      }
      for (int y = 0; y < h; y++) out[y * w + x] = col[y];
    }
  }
}
