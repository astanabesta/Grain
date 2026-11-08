package dev.grain.photo;

/** Pure geometry, shared by preview layout, focus mapping and final JPEG cropping. */
public final class Framing {
  private Framing() {}

  public static final String[] RATIOS = {"3:4", "1:1", "9:16"};

  public static double zoom(double z) {
    return Double.isFinite(z) ? Math.max(1, Math.min(5, z)) : 1;
  }

  public static String ratio(String value) {
    for (String r : RATIOS) if (r.equals(value)) return r;
    return "3:4";
  }

  public static double portraitRatio(String value) {
    return switch (ratio(value)) {
      case "1:1" -> 1;
      case "9:16" -> 9.0 / 16;
      default -> .75;
    };
  }

  public static double[] fit(double width, double height, String ratio) {
    double r = portraitRatio(ratio), w = Math.min(width, height * r);
    return new double[] {w, w / r};
  }

  /** Center-crop in upright source pixels. No fixed export size or artificial upscaling. */
  public static int[] crop(int width, int height, String ratio, double zoom) {
    double r = portraitRatio(ratio), w = Math.min(width, height * r) / zoom(zoom), h = w / r;
    int cw = Math.max(1, Math.min(width, (int) Math.round(w)));
    int ch = Math.max(1, Math.min(height, (int) Math.round(h)));
    return new int[] {(width - cw) / 2, (height - ch) / 2, cw, ch};
  }

  /** Map a point in a center-cropped, zoomed preview to normalized full source coordinates. */
  public static double[] sourcePoint(
      double x, double y, int width, int height, String ratio, double zoom) {
    int[] c = crop(width, height, ratio, zoom);
    return new double[] {
      (c[0] + Math.max(0, Math.min(1, x)) * c[2]) / width,
      (c[1] + Math.max(0, Math.min(1, y)) * c[3]) / height
    };
  }

  /**
   * Maps an upright crop rectangle {@code [cx, cy, cw, ch]} back to unrotated JPEG sensor pixel
   * coordinates {@code [sx, sy, sw, sh]} for EXIF orientations 1 (0 deg), 3 (180 deg), 6 (90 deg
   * CW) and 8 (270 deg CW), or {@code null} for mirrored orientations.
   */
  public static int[] sensorCropRect(int rawW, int rawH, int orientation, int[] uprightCrop) {
    int cx = uprightCrop[0], cy = uprightCrop[1], cw = uprightCrop[2], ch = uprightCrop[3];
    return switch (orientation) {
      case 0, 1 -> new int[] {cx, cy, cw, ch};
      case 3 -> new int[] {rawW - cx - cw, rawH - cy - ch, cw, ch};
      case 6 -> new int[] {cy, rawH - cx - cw, ch, cw};
      case 8 -> new int[] {rawW - cy - ch, cx, ch, cw};
      default -> null;
    };
  }
}
