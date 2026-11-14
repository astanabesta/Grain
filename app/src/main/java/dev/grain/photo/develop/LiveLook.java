package dev.grain.photo.develop;

/**
 * Bakes a recipe's full output transform into a compact 3D LUT (sRGB in → sRGB out) so the live
 * viewfinder can show "what kind of output am I getting" for every recipe, on the GPU. The math
 * is not reimplemented here: each grid colour is pushed through the exact develop-side preview
 * transform ({@link LutPreview#apply}) — tone model, film LUT, blue FX, sensor calibration or
 * Apple's Smart HDR 5 style path — so the viewfinder and the saved photo agree.
 *
 * <p>17³ is the native Fujifilm LUT lattice; trilinear interpolation in the shader keeps
 * mid-grid error well below a JPEG's quantisation noise. Generating 4 913 texels costs roughly
 * as much as decoding one small thumbnail — done once per recipe, off the UI thread.
 */
public final class LiveLook {
  public static final int SIZE = 17;

  private LiveLook() {}

  /** Result: RGB bytes, n×n×n, blue fastest — straight into a GL_TEXTURE_3D (GL_RGB8). */
  public static final class Look {
    public final byte[] rgb;
    public final int size;

    Look(byte[] rgb, int size) {
      this.rgb = rgb;
      this.size = size;
    }
  }

  /**
   * Bake the look for a RAW-developed recipe. Same caller pattern as the film-sheet preview:
   * the film-simulation LUT, and the recipe itself when the Apple or sensor-calibrated path is
   * going to shape it (both are consulted inside {@link LutPreview#apply}). Never returns null;
   * null out via the caller when the recipe has no developer-side look (plain ISP recipes).
   */
  public static Look bake(CubeLut lut, FujiRecipe recipe) {
    int n = SIZE;
    int count = n * n * n;
    int[] grid = new int[count];
    int i = 0;
    for (int b = 0; b < n; b++) {
      int cb = b * 255 / (n - 1);
      for (int g = 0; g < n; g++) {
        int cg = g * 255 / (n - 1);
        for (int r = 0; r < n; r++, i++) {
          grid[i] = 0xff000000 | ((r * 255 / (n - 1)) << 16) | (cg << 8) | cb;
        }
      }
    }
    LutPreview.apply(grid, lut, recipe);
    byte[] rgb = new byte[count * 3];
    for (i = 0; i < count; i++) {
      int p = grid[i];
      rgb[i * 3] = (byte) ((p >> 16) & 0xff);
      rgb[i * 3 + 1] = (byte) ((p >> 8) & 0xff);
      rgb[i * 3 + 2] = (byte) (p & 0xff);
    }
    return new Look(rgb, n);
  }
}
