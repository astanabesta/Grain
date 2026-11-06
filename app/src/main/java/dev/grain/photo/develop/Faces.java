package dev.grain.photo.develop;

/**
 * Faces for Grain's face exposure: which face is metered, and the coordinate mappings from the
 * camera's face detector (active-array rectangles) and from a face found in the upright reference
 * JPEG to RAW pixels. Pure Java so it is unit-tested off the device.
 *
 * <p>Fujifilm's manuals: "Intelligent Face Detection sets focus and exposure for human faces
 * anywhere in the frame ... If there is more than one face in the frame, the camera will select the
 * face closest to the center". Grain does the same in RAW development.
 */
public final class Faces {
  /** Camera2 recommends ignoring faces below this confidence (1..100) for point-and-shoot use. */
  public static final int MIN_SCORE = 50;
  /** Smallest metered face, as a fraction of the frame's short side (smaller faces are ignored). */
  public static final double MIN_SIZE = 0.025;

  private Faces() {}

  /**
   * Index of the face to meter: among faces with a usable confidence and size, the one whose centre
   * is closest to the frame centre; -1 if there is none. {@code faces} holds {left, top, right,
   * bottom} per face in RAW pixels; {@code scores} (1..100, may be null) one value per face.
   */
  public static int pick(int[] faces, int[] scores, int width, int height) {
    if (faces == null) return -1;
    int best = -1;
    double bestDistance = Double.MAX_VALUE, minSide = MIN_SIZE * Math.min(width, height);
    for (int i = 0; i + 3 < faces.length; i += 4) {
      int k = i / 4;
      if (scores != null && k < scores.length && scores[k] < MIN_SCORE) continue;
      int l = Math.max(0, faces[i]), t = Math.max(0, faces[i + 1]);
      int r = Math.min(width, faces[i + 2]), b = Math.min(height, faces[i + 3]);
      if (r - l < minSide || b - t < minSide) continue;
      double dx = ((l + r) * 0.5 - width * 0.5) / width, dy = ((t + b) * 0.5 - height * 0.5) / height;
      double distance = dx * dx + dy * dy;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = k;
      }
    }
    return best;
  }

  /**
   * A Camera2 face rectangle to RAW pixels. Face rectangles use the active-array coordinate system,
   * with (0, 0) at the active array's top-left. {@code offsetX/offsetY} is where that corner sits in
   * the RAW buffer (the active array's left/top when the buffer is the full pixel array); when the
   * RAW buffer is not a crop of the active array at 1:1 the rectangle is scaled from {@code
   * activeWidth x activeHeight} to {@code rawWidth x rawHeight} instead. Returns null if the result
   * is empty after clamping to the frame.
   */
  public static int[] activeToRaw(
      int left, int top, int right, int bottom,
      int activeWidth, int activeHeight, int offsetX, int offsetY, boolean scaled,
      int rawWidth, int rawHeight) {
    double sx = 1, sy = 1;
    if (scaled) {
      if (activeWidth <= 0 || activeHeight <= 0) return null;
      sx = rawWidth / (double) activeWidth;
      sy = rawHeight / (double) activeHeight;
      offsetX = 0;
      offsetY = 0;
    }
    return clamp(offsetX + left * sx, offsetY + top * sy, offsetX + right * sx, offsetY + bottom * sy,
        rawWidth, rawHeight);
  }

  /**
   * android.media.FaceDetector reports the point between the eyes and the eye distance; this is the
   * face box a camera detector would report around it (cheek to cheek, brows to chin), {left, top,
   * right, bottom} in the same coordinates.
   */
  public static double[] boxFromEyes(double midX, double midY, double eyeDistance) {
    return new double[] {
      midX - 1.1 * eyeDistance, midY - 1.0 * eyeDistance, midX + 1.1 * eyeDistance, midY + 1.4 * eyeDistance
    };
  }

  /**
   * A rectangle in an upright picture (the sensor image rotated clockwise by {@code degrees}, then
   * scaled to {@code sensorWidth x sensorHeight} before rotation) back to RAW pixels of a {@code
   * rawWidth x rawHeight} frame with the same field of view. Returns null if empty after clamping.
   */
  public static int[] uprightToRaw(
      double left, double top, double right, double bottom, int degrees,
      int sensorWidth, int sensorHeight, int rawWidth, int rawHeight) {
    double[] a = toSensor(left, top, degrees, sensorWidth, sensorHeight);
    double[] b = toSensor(right, bottom, degrees, sensorWidth, sensorHeight);
    double sx = rawWidth / (double) sensorWidth, sy = rawHeight / (double) sensorHeight;
    return clamp(Math.min(a[0], b[0]) * sx, Math.min(a[1], b[1]) * sy,
        Math.max(a[0], b[0]) * sx, Math.max(a[1], b[1]) * sy, rawWidth, rawHeight);
  }

  /** Inverse of a clockwise rotation by {@code degrees}, in continuous (edge) coordinates. */
  static double[] toSensor(double x, double y, int degrees, int w, int h) {
    switch (((degrees % 360) + 360) % 360) {
      case 90:
        return new double[] {y, h - x};
      case 180:
        return new double[] {w - x, h - y};
      case 270:
        return new double[] {w - y, x};
      default:
        return new double[] {x, y};
    }
  }

  private static int[] clamp(double l, double t, double r, double b, int width, int height) {
    int left = (int) Math.max(0, Math.min(width, Math.round(Math.min(l, r))));
    int right = (int) Math.max(0, Math.min(width, Math.round(Math.max(l, r))));
    int top = (int) Math.max(0, Math.min(height, Math.round(Math.min(t, b))));
    int bottom = (int) Math.max(0, Math.min(height, Math.round(Math.max(t, b))));
    return right - left < 2 || bottom - top < 2 ? null : new int[] {left, top, right, bottom};
  }
}
