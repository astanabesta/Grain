package dev.grain.photo;

import static org.junit.Assert.assertTrue;

import dev.grain.photo.develop.PortraitBokeh;
import org.junit.Test;

/** Portrait bokeh engine: blurred background, untouched sharp subject, smooth focus transition. */
public class PortraitBokehTest {

  @Test
  public void backgroundBlursWhileSubjectStaysPixelSharpWithSoftTransition() {
    int w = 192, h = 144;
    int[] img = new int[w * h];
    long rng = 7654321L;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        double nz = (((rng >>> 33) & 1023) / 1023.0 - 0.5) * 44; // strong background "noise"
        boolean person = personEllipse(x, y);
        double l;
        if (person) {
          l = 60 + ((x % 8) < 2 ? 16 : -6); // fine subject stripes
        } else {
          l = 150 + nz; // bright noisy background
        }
        int v = (int) Math.round(Math.max(0, Math.min(255, l)));
        img[y * w + x] = 0xff000000 | (v << 16) | (v << 8) | v;
      }
    }
    // One hot backlit highlight patch (becomes a soft bokeh disc halo)
    int hx = 150, hy = 62;
    for (int dy = 0; dy < 5; dy++)
      for (int dx = 0; dx < 5; dx++) img[(hy + dy) * w + hx + dx] = 0xffffffff;

    int qw = (w + 3) / 4, qh = (h + 3) / 4;
    float[] mask = new float[qw * qh];
    for (int y = 0; y < qh; y++)
      for (int x = 0; x < qw; x++) mask[y * qw + x] = personEllipse(x * 4 + 2, y * 4 + 2) ? 1f : 0f;

    int[] out = new int[w * h];
    PortraitBokeh.applyRadius(img, w, h, mask, qw, qh, 22f, out);
    StringBuilder dbg = new StringBuilder("DBG row y=" + hy + ":");
    for (int x = 128; x <= 188; x += 4)
      dbg.append(" ").append(Math.round(lumaAt(out, w, x, hy)));
    System.out.println(dbg.toString());

    double d1FgBefore = d1(img, w, 60, 92, 46, 100);
    double d1FgAfter = d1(out, w, 60, 92, 46, 100);
    double d1BgBefore = d1(img, w, 130, 186, 84, 132);
    double d1BgAfter = d1(out, w, 130, 186, 84, 132);
    assertTrue("subject stays pixel-sharp (" + d1FgAfter + " vs " + d1FgBefore + ")",
        d1FgAfter >= 0.92 * d1FgBefore);
    assertTrue("background bokeh crushes noise (" + d1BgAfter + " vs " + d1BgBefore + ")",
        d1BgAfter <= 0.32 * d1BgBefore);

    // Base-layer bokeh: the hot patch dilutes into a wide soft halo (its energy spreads over the
    // disc area, it never clips into a hard blob), and the disc's total light is conserved.
    double peak = lumaAt(out, w, hx + 2, hy + 2);
    assertTrue("highlight peak dilutes into disc (" + peak + ")", peak < 225.0);
    double inMass = 0, outMass = 0;
    for (int y = 42; y < 88; y++)
      for (int x = 126; x < 176; x++) {
        inMass += srgbLin(img[y * w + x] & 255);
        outMass += srgbLin(out[y * w + x] & 255);
      }
    double ratio = outMass / inMass;
    assertTrue("disc light is conserved, not clipped (" + ratio + ")", ratio > 0.86 && ratio < 1.09);

    // Soft optical transition at the subject boundary (no 1-px seam step).
    // Right edge of the ellipse near y=72 sits at x≈95; luma should progress, not jump.
    double atEdge = lumaAt(out, w, 97, 72);
    double deepBg = lumaAt(out, w, 112, 72);
    double insideFg = lumaAt(out, w, 88, 72);
    assertTrue(
        "transition zone between sharp subject and bokeh ("
            + insideFg + " -> " + atEdge + " -> " + deepBg + ")",
        atEdge > insideFg - 30 && atEdge < deepBg + 40);
    double seamStep = Math.abs(lumaAt(out, w, 96, 72) - lumaAt(out, w, 95, 72));
    assertTrue("no hard 1px seam at the silhouette (" + seamStep + ")", seamStep < 88.0);
  }

  /**
   * Depth-aware defocus: a side object at the SAME depth as the subject must stay sharp (real
   * optics) even though it is not part of the person mask, while the far background melts. The
   * boundary-distance ramp could not do this — the monocular depth map can.
   */
  @Test
  public void depthKeepsSamePlaneSharpFarPlaneMeltsSmoothly() {
    int w = 192, h = 144;
    int[] img = new int[w * h];
    long rng = 123456789L;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        double nz = (((rng >>> 33) & 1023) / 1023.0 - 0.5) * 44;
        boolean person = personEllipse(x, y);
        boolean bed = x >= 116 && x < 176 && y >= 96 && y < 140; // same-plane object beside subject
        double l = bed ? 80 + nz * 0.4 : person ? 60 + ((x % 8) < 2 ? 16 : -6) : 150 + nz;
        int v = (int) Math.round(Math.max(0, Math.min(255, l)));
        img[y * w + x] = 0xff000000 | (v << 16) | (v << 8) | v;
      }
    }
    int qw = (w + 3) / 4, qh = (h + 3) / 4;
    float[] mask = new float[qw * qh];
    float[] depth = new float[qw * qh];
    for (int y = 0; y < qh; y++)
      for (int x = 0; x < qw; x++) {
        boolean person = personEllipse(x * 4 + 2, y * 4 + 2);
        mask[y * qw + x] = person ? 1f : 0f;
        depth[y * qw + x] = person ? 0.85f : 0.25f;
      }
    // Bed at subject depth (not in the person mask).
    for (int y = 24; y < 35; y++)
      for (int x = 29; x < 44; x++) depth[y * qw + x] = 0.83f;

    int[] out = new int[w * h];
    PortraitBokeh.apply(img, w, h, mask, qw, qh, 1.4f, out, depth);

    double bedBefore = d1(img, w, 120, 172, 100, 136), bedAfter = d1(out, w, 120, 172, 100, 136);
    double bgBefore = d1(img, w, 60, 92, 4, 30), bgAfter = d1(out, w, 60, 92, 4, 30);
    System.out.println("DBG depth: bg " + bgBefore + "->" + bgAfter + " bed " + bedBefore + "->" + bedAfter);
    assertTrue("far background melts (" + bgAfter + " vs " + bgBefore + ")", bgAfter <= 0.45 * bgBefore);
    assertTrue("same-plane object stays sharp (" + bedAfter + " vs " + bedBefore + ")", bedAfter >= 0.80 * bedBefore);
    double fgBefore = d1(img, w, 60, 92, 46, 100), fgAfter = d1(out, w, 60, 92, 46, 100);
    assertTrue("subject stays pixel-sharp (" + fgAfter + " vs " + fgBefore + ")", fgAfter >= 0.92 * fgBefore);

    // Depth absent -> identical results to the legacy boundary ramp (regression safety).
    int[] outB = new int[w * h];
    PortraitBokeh.apply(img, w, h, mask, qw, qh, 1.4f, outB, null);
    double bedChamfer = d1(outB, w, 120, 172, 100, 136);
    assertTrue("chamfer fallback blurs same-plane object (" + bedChamfer + " vs " + bedBefore + ")",
        bedChamfer < bedAfter * 0.95f);
  }

  @Test
  public void apertureControlsDiscRadius() {
    assertTrue(PortraitBokeh.maxRadiusPx(3060, 0.95f) > PortraitBokeh.maxRadiusPx(3060, 2.8f));
    assertTrue(PortraitBokeh.maxRadiusPx(3060, 2.8f) > PortraitBokeh.maxRadiusPx(3060, 8f));
    assertTrue(PortraitBokeh.maxRadiusPx(3060, 8f) > 8f);
    assertTrue(PortraitBokeh.maxRadiusPx(3060, 0.95f) <= 0.051f * 3060);
  }

  private static boolean personEllipse(int x, int y) {
    double dx = (x - 70) / 26.0, dy = (y - 66) / 40.0;
    return dx * dx + dy * dy <= 1.0;
  }

  private static double srgbLin(int c255) {
    double c = c255 / 255.0;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static double lumaAt(int[] argb, int w, int x, int y) {
    int p = argb[y * w + x];
    return 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
  }

  private static double d1(int[] argb, int w, int x0, int x1, int y0, int y1) {
    double sum = 0;
    long n = 0;
    for (int y = y0; y < y1; y++)
      for (int x = x0; x < x1; x++) {
        sum += Math.abs(lumaAt(argb, w, x + 1, y) - lumaAt(argb, w, x, y));
        n++;
      }
    return sum / Math.max(1, n);
  }
}
