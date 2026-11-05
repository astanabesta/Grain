package dev.grain.photo;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.MandatoryStreamCombination;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.util.Pair;
import android.util.Rational;
import android.util.Size;
import androidx.exifinterface.media.ExifInterface;
import dev.grain.photo.develop.RawFrame;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Camera2 RAW_SENSOR support: capability probe, frame copy with calibration metadata, DNG. */
final class RawSupport {
  private RawSupport() {}

  static boolean supported(CameraCharacteristics chars, StreamConfigurationMap map) {
    int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
    boolean raw = false;
    if (caps != null)
      for (int c : caps)
        if (c == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) raw = true;
    Integer cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
    return raw
        && map != null
        && rawSize(map) != null
        && cfa != null
        && cfa >= CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        && cfa <= CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR
        && chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null;
  }

  static Size rawSize(StreamConfigurationMap map) {
    Size[] sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
    if (sizes == null || sizes.length == 0) return null;
    Size best = sizes[0];
    for (Size s : sizes)
      if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
    return best;
  }

  static double[] matrix(ColorSpaceTransform t) {
    if (t == null) return null;
    double[] m = new double[9];
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 3; c++) m[r * 3 + c] = t.getElement(c, r).doubleValue();
    return m;
  }

  static JSONArray json(double[] v) {
    JSONArray a = new JSONArray();
    if (v == null) return a;
    for (double d : v) {
      try {
        a.put(Math.round(d * 1e6) / 1e6);
      } catch (Exception ignored) {
      }
    }
    return a;
  }

  /** Copies the RAW plane and gathers everything the developer needs. Call before image.close(). */
  static RawFrame frame(Image image, CameraCharacteristics chars, CaptureResult result) {
    Image.Plane plane = image.getPlanes()[0];
    int w = image.getWidth(), h = image.getHeight();
    int rowStride = plane.getRowStride() / 2, pixelStride = plane.getPixelStride();
    if (pixelStride != 2) throw new IllegalStateException("Unexpected RAW pixel stride " + pixelStride);
    ByteBuffer bytes = plane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
    ShortBuffer shorts = bytes.asShortBuffer();
    short[] data = new short[w * h];
    if (rowStride == w) shorts.get(data, 0, w * h);
    else
      for (int y = 0; y < h; y++) {
        shorts.position(y * rowStride);
        shorts.get(data, y * w, w);
      }
    RawFrame f = new RawFrame(data, w, h, w);
    Integer cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
    f.cfa = cfa == null ? RawFrame.RGGB : cfa;
    float[] dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
    BlackLevelPattern pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
    for (int k = 0; k < 4; k++) {
      if (dynamicBlack != null && dynamicBlack.length == 4) f.black[k] = dynamicBlack[k];
      else if (pattern != null) f.black[k] = pattern.getOffsetForIndex(k & 1, k >> 1);
    }
    Integer dynamicWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
    Integer white = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
    f.white = dynamicWhite != null && dynamicWhite > 0 ? dynamicWhite : white != null ? white : 1023;
    LensShadingMap map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
    if (map != null && map.getColumnCount() >= 2 && map.getRowCount() >= 2) {
      f.shadingColumns = map.getColumnCount();
      f.shadingRows = map.getRowCount();
      f.shading = new float[map.getGainFactorCount()];
      map.copyGainFactors(f.shading, 0);
      Rect pre = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
      Size pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
      if (pre != null && pixels != null && pixels.getWidth() == w && pixels.getHeight() == h) {
        f.shadingLeft = pre.left;
        f.shadingTop = pre.top;
        f.shadingWidth = pre.width();
        f.shadingHeight = pre.height();
      } else {
        f.shadingLeft = 0;
        f.shadingTop = 0;
        f.shadingWidth = w;
        f.shadingHeight = h;
      }
    }
    Rational[] neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
    if (neutral != null && neutral.length == 3)
      f.neutral =
          new double[] {neutral[0].doubleValue(), neutral[1].doubleValue(), neutral[2].doubleValue()};
    f.colorMatrix1 = matrix(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
    f.colorMatrix2 = matrix(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
    f.forwardMatrix1 = matrix(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1));
    f.forwardMatrix2 = matrix(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
    f.calibration1 = matrix(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
    f.calibration2 = matrix(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));
    Integer i1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
    Byte i2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
    f.illuminant1 = i1 == null ? 21 : i1;
    f.illuminant2 = i2 == null || f.colorMatrix2 == null ? f.illuminant1 : i2;
    if (f.colorMatrix2 == null) {
      f.forwardMatrix2 = null;
      f.calibration2 = null;
    }
    Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
    f.iso = iso == null ? 100 : iso;
    Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
    f.exposureNs = exposure == null ? 0 : exposure;
    return f;
  }

  /** Summary of the frame's calibration for the photo receipt. */
  static JSONObject describe(RawFrame f, CaptureResult result) {
    JSONObject o = new JSONObject();
    try {
      o.put("width", f.width).put("height", f.height).put("cfa", new String[] {"RGGB", "GRBG", "GBRG", "BGGR"}[f.cfa]);
      JSONArray black = new JSONArray();
      for (float b : f.black) black.put(b);
      o.put("blackLevel", black).put("whiteLevel", f.white);
      o.put("asShotNeutral", json(f.neutral));
      o.put("colorMatrix1", json(f.colorMatrix1)).put("colorMatrix2", json(f.colorMatrix2));
      o.put("forwardMatrix1", json(f.forwardMatrix1)).put("forwardMatrix2", json(f.forwardMatrix2));
      o.put("calibration1", json(f.calibration1)).put("calibration2", json(f.calibration2));
      o.put("illuminant1", f.illuminant1).put("illuminant2", f.illuminant2);
      o.put(
          "lensShadingMap",
          f.shading == null
              ? "missing"
              : f.shadingColumns + "x" + f.shadingRows + " over " + f.shadingWidth + "x" + f.shadingHeight
                  + "+" + f.shadingLeft + "+" + f.shadingTop);
      o.put("iso", f.iso).put("exposureNs", f.exposureNs);
      Integer boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
      if (boost != null) o.put("postRawSensitivityBoost", boost);
      Pair<Double, Double>[] noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE);
      if (noise != null) {
        JSONArray n = new JSONArray();
        for (Pair<Double, Double> p : noise) n.put(new JSONArray().put(p.first).put(p.second));
        o.put("noiseProfile", n);
      }
    } catch (Exception ignored) {
    }
    return o;
  }

  /** Device probe for diagnostics: what this camera's RAW path offers. */
  static JSONObject probe(CameraCharacteristics chars, StreamConfigurationMap map) {
    JSONObject o = new JSONObject();
    try {
      o.put("rawCapability", supported(chars, map));
      Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.RAW_SENSOR);
      JSONArray s = new JSONArray();
      if (sizes != null) for (Size z : sizes) s.put(z.getWidth() + "x" + z.getHeight());
      o.put("rawSensorSizes", s);
      Integer cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
      o.put("cfa", cfa == null ? JSONObject.NULL : cfa);
      o.put("whiteLevel", chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
      BlackLevelPattern p = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
      if (p != null) {
        JSONArray b = new JSONArray();
        for (int k = 0; k < 4; k++) b.put(p.getOffsetForIndex(k & 1, k >> 1));
        o.put("blackLevelPattern", b);
      }
      o.put("referenceIlluminant1", chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1));
      Byte i2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
      o.put("referenceIlluminant2", i2 == null ? JSONObject.NULL : (int) i2);
      o.put("colorMatrix1", json(matrix(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))));
      o.put("colorMatrix2", json(matrix(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))));
      o.put("forwardMatrix1", json(matrix(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))));
      o.put("forwardMatrix2", json(matrix(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))));
      o.put("calibration1", json(matrix(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))));
      o.put("calibration2", json(matrix(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))));
      Rect pre = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
      Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
      Size pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
      o.put("pixelArray", pixels == null ? JSONObject.NULL : pixels.toString());
      o.put("preCorrectionActiveArray", pre == null ? JSONObject.NULL : pre.flattenToString());
      o.put("activeArray", active == null ? JSONObject.NULL : active.flattenToString());
      int[] shadingModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES);
      JSONArray sm = new JSONArray();
      if (shadingModes != null) for (int m : shadingModes) sm.put(m);
      o.put("lensShadingMapModes", sm);
      List<MandatoryStreamCombination> combos =
          java.util.Arrays.asList(
              chars.get(CameraCharacteristics.SCALER_MANDATORY_STREAM_COMBINATIONS) == null
                  ? new MandatoryStreamCombination[0]
                  : chars.get(CameraCharacteristics.SCALER_MANDATORY_STREAM_COMBINATIONS));
      int rawCombos = 0;
      for (MandatoryStreamCombination c : combos)
        for (MandatoryStreamCombination.MandatoryStreamInformation i : c.getStreamsInformation())
          if (i.getFormat() == ImageFormat.RAW_SENSOR) {
            rawCombos++;
            break;
          }
      o.put("mandatoryCombinationsWithRaw", rawCombos);
    } catch (Exception e) {
      try {
        o.put("probeError", e.toString());
      } catch (Exception ignored) {
      }
    }
    return o;
  }

  static void writeDng(
      CameraCharacteristics chars, CaptureResult result, Image image, int orientationDegrees, File out)
      throws Exception {
    try (DngCreator dng = new DngCreator(chars, result);
        OutputStream os = new FileOutputStream(out)) {
      dng.setOrientation(exifOrientation(orientationDegrees));
      dng.writeImage(os, image);
    }
  }

  static int exifOrientation(int degrees) {
    switch (((degrees % 360) + 360) % 360) {
      case 90:
        return ExifInterface.ORIENTATION_ROTATE_90;
      case 180:
        return ExifInterface.ORIENTATION_ROTATE_180;
      case 270:
        return ExifInterface.ORIENTATION_ROTATE_270;
      default:
        return ExifInterface.ORIENTATION_NORMAL;
    }
  }
}
