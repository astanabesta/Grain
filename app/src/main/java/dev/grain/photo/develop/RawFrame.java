package dev.grain.photo.develop;

/**
 * One Bayer RAW frame plus the calibration metadata the camera HAL published with it. Pure Java so
 * the developer can be tested on a desktop JVM.
 */
public final class RawFrame {
  public static final int RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3;
  public final short[] data;
  public final int width, height, rowStride;
  public int cfa = RGGB;
  /** Black level per 2x2 CFA position, index (row & 1) * 2 + (column & 1). */
  public final float[] black = {64, 64, 64, 64};
  public float white = 1023;
  /** Lens-shading gains, rows x columns x [R, G_even, G_odd, B] (Camera2 LensShadingMap order). */
  public float[] shading;
  public int shadingColumns, shadingRows;
  /** RAW-pixel rectangle the shading map spans. */
  public int shadingLeft, shadingTop, shadingWidth, shadingHeight;
  public double[] neutral = {1, 1, 1};
  public double[] colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2, calibration1, calibration2;
  public int illuminant1 = 21, illuminant2 = 21;
  public int iso = 100;
  public long exposureNs;
  /**
   * Camera2 noise profile per CFA position (row-major 2x2, same indexing as {@link #black}): the
   * variance of a pixel normalised to [0, 1] after black subtraction is noiseS * x + noiseO. Null
   * when the HAL did not report it.
   */
  public double[] noiseS, noiseO;
  /**
   * Faces in this frame for face exposure: {left, top, right, bottom} per face in RAW pixels, and a
   * detection confidence of 1..100 per face (null = unknown). Null or empty when none was found.
   */
  public int[] faces, faceScores;
  /** Where {@link #faces} came from, for the receipt ("" when none). */
  public String faceSource = "";
  /**
   * How many of the camera's detected faces carried the optional landmark fields (v0.5.0 focus
   * probe): {@link #faceEyes} = both eye positions, {@link #faceIds} = a tracker id,
   * {@link #faceMouths} = a mouth position. Zero when the faces came from the phone JPEG's
   * FaceDetector instead of the camera's statistics.
   */
  public int faceEyes, faceIds, faceMouths;
  /**
   * How many back-to-back frames were averaged into {@link #data} by {@link RawStack} (1 = plain
   * single capture). The frame-measured noise model uses it to widen the clamp floor, and the
   * receipt/EXIF report it.
   */
  public int stackCount = 1;

  public RawFrame(short[] data, int width, int height, int rowStride) {
    if (width < 8 || height < 8 || (width & 1) != 0 || (height & 1) != 0)
      throw new IllegalArgumentException("RAW size must be even and at least 8x8");
    if (data.length < (long) rowStride * (height - 1) + width)
      throw new IllegalArgumentException("RAW buffer too small");
    this.data = data;
    this.width = width;
    this.height = height;
    this.rowStride = rowStride;
  }

  /** 0 red, 1 green, 2 blue at a pixel position. */
  public int color(int x, int y) {
    int k = ((y & 1) << 1) | (x & 1);
    switch (cfa) {
      case RGGB:
        return k == 0 ? 0 : k == 3 ? 2 : 1;
      case GRBG:
        return k == 1 ? 0 : k == 2 ? 2 : 1;
      case GBRG:
        return k == 2 ? 0 : k == 1 ? 2 : 1;
      case BGGR:
        return k == 3 ? 0 : k == 0 ? 2 : 1;
      default:
        throw new IllegalStateException("Unsupported CFA " + cfa);
    }
  }

  /** Camera2 lens-shading channel for a pixel position: R 0, G on even rows 1, G on odd rows 2, B 3. */
  public int shadingChannel(int x, int y) {
    int c = color(x, y);
    return c == 0 ? 0 : c == 2 ? 3 : (y & 1) == 0 ? 1 : 2;
  }
}
