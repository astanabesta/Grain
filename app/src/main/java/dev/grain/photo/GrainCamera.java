package dev.grain.photo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.*;
import android.view.*;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.RawFrame;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;
import org.json.*;

/**
 * Native Camera2 still engine. ISP recipes use direct capture requests (approach inspected in
 * Zircon Cinema). Fujifilm recipes capture RAW_SENSOR plus the phone's own JPEG for reference and
 * hand the RAW to the on-device developer. No video engine, P010 routing or JPEG color filter.
 */
public final class GrainCamera {
  public interface Listener {
    void ready(JSONObject capabilities, int sourceWidth, int sourceHeight);

    void error(String message);

    void captured(byte[] jpeg, Snapshot settings, JSONObject receipt);

    /** RAW recipe capture: sensor data + calibration, the phone's JPEG and an optional DNG. */
    void capturedRaw(RawCapture capture, Snapshot settings, JSONObject receipt);

    void status(String message);
  }

  public static final class Snapshot {
    public final int recipe, strength;
    public final double zoom;
    public final String ratio;
    public final boolean keepFull;
    public final float ev;
    /** The RAW film as tuned in the app (null for ISP looks): what capture and development use. */
    public final FujiRecipe fuji;

    public Snapshot(
        int recipe, int strength, double zoom, String ratio, boolean keepFull, float ev) {
      this(recipe, strength, zoom, ratio, keepFull, ev, null);
    }

    public Snapshot(
        int recipe,
        int strength,
        double zoom,
        String ratio,
        boolean keepFull,
        float ev,
        FujiRecipe fuji) {
      this.fuji = fuji;
      this.recipe = recipe;
      this.strength = strength;
      this.zoom = Framing.zoom(zoom);
      this.ratio = Framing.ratio(ratio);
      this.keepFull = keepFull;
      this.ev = ev;
    }
  }

  public static final class RawCapture {
    public final RawFrame frame;
    public final byte[] phoneJpeg;
    public final File dng;
    public final int orientationDegrees;

    RawCapture(RawFrame frame, byte[] phoneJpeg, File dng, int orientationDegrees) {
      this.frame = frame;
      this.phoneJpeg = phoneJpeg;
      this.dng = dng;
      this.orientationDegrees = orientationDegrees;
    }
  }

  private final Activity activity;
  private final TextureView texture;
  private final Listener listener;
  private final CameraManager manager;
  private final Handler main = new Handler(Looper.getMainLooper());
  private HandlerThread thread;
  private Handler handler;
  private CameraDevice device;
  private CameraCaptureSession session;
  private CameraCharacteristics chars;
  private ImageReader jpegReader;
  private Surface previewSurface;
  private Size jpegSize, previewSize;
  private int sensorRotation;
  private boolean front;
  private volatile boolean closed = true;
  private boolean inFlight, opening, safeMode;
  private String cameraId;
  private Set<CaptureRequest.Key<?>> requestKeys = Collections.emptySet();
  private volatile Snapshot current = new Snapshot(0, 80, 1, "3:4", true, 0), pending;
  private byte[] pendingBytes;
  private long pendingImageTime;
  private TotalCaptureResult pendingResult;
  private JSONObject pendingApplied;
  private final ArrayList<String> warnings = new ArrayList<>();
  private JSONObject capabilities = new JSONObject();
  private volatile int generation;
  private boolean maximumResolution;
  private volatile boolean neutral;
  private volatile boolean closing;
  private ImageReader rawReader;
  private Size rawSize;
  private Image pendingRaw;
  private long pendingRawTime;
  private boolean pendingWantsRaw;
  /** Physical device rotation (clockwise degrees) from the activity, and its value at the shutter. */
  private volatile int deviceRotation;
  private int pendingRotation;
  private String rawUnavailable = "not probed";
  private volatile File dngDirectory;

  public GrainCamera(Activity activity, TextureView texture, Listener listener) {
    this.activity = activity;
    this.texture = texture;
    this.listener = listener;
    manager = (CameraManager) activity.getSystemService(Activity.CAMERA_SERVICE);
  }

  public void open(boolean frontFacing, boolean maximum) {
    if (closing) {
      main.postDelayed(
          () -> {
            if (!activity.isFinishing() && texture.isAvailable() && activity.hasWindowFocus())
              open(frontFacing, maximum);
          },
          100);
      return;
    }
    if (opening || !closed) return;
    if (activity.checkSelfPermission(Manifest.permission.CAMERA)
        != PackageManager.PERMISSION_GRANTED) return;
    front = frontFacing;
    maximumResolution = maximum;
    closed = false;
    opening = true;
    safeMode = false;
    thread = new HandlerThread("GrainCamera");
    thread.start();
    handler = new Handler(thread.getLooper());
    int token = ++generation;
    handler.post(() -> openInternal(token));
  }

  @android.annotation.SuppressLint("MissingPermission")
  private void openInternal(int token) {
    if (closed || token != generation) return;
    try {
      cameraId = pickCamera();
      chars = manager.getCameraCharacteristics(cameraId);
      requestKeys = new HashSet<>(chars.getAvailableCaptureRequestKeys());
      Integer rotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
      sensorRotation = rotation == null ? 90 : rotation;
      StreamConfigurationMap streams =
          chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      if (streams == null) throw new Exception("No public camera stream map");
      jpegSize = chooseJpeg(streams.getOutputSizes(ImageFormat.JPEG), maximumResolution);
      previewSize = choosePreview(streams.getOutputSizes(SurfaceTexture.class), jpegSize);
      jpegReader =
          ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
      jpegReader.setOnImageAvailableListener(
          reader -> {
            try (Image image = reader.acquireNextImage()) {
              if (image == null || closed || token != generation) return;
              ByteBuffer b = image.getPlanes()[0].getBuffer();
              byte[] bytes = new byte[b.remaining()];
              b.get(bytes);
              if (!inFlight) return;
              pendingBytes = bytes;
              pendingImageTime = image.getTimestamp();
              tryFinish();
            } catch (Exception e) {
              failCapture("Could not read camera JPEG: " + e.getMessage());
            }
          },
          handler);
      rawReader = null;
      rawSize = null;
      if (front) rawUnavailable = "RAW recipes use the rear camera in this build";
      else if (!RawSupport.supported(chars, streams))
        rawUnavailable =
            "Camera2 exposes no usable RAW_SENSOR stream here (Bayer, up to 26 MP, with colour calibration)";
      else {
        rawSize = RawSupport.rawSize(streams);
        rawReader =
            ImageReader.newInstance(
                rawSize.getWidth(), rawSize.getHeight(), ImageFormat.RAW_SENSOR, 2);
        rawReader.setOnImageAvailableListener(
            reader -> {
              Image image = null;
              try {
                image = reader.acquireNextImage();
                if (image == null) return;
                if (closed || token != generation || !inFlight || !pendingWantsRaw) return;
                if (pendingRaw != null) pendingRaw.close();
                pendingRaw = image;
                pendingRawTime = image.getTimestamp();
                image = null;
                tryFinish();
              } catch (Exception e) {
                failCapture("Could not read RAW: " + e.getMessage());
              } finally {
                if (image != null) image.close();
              }
            },
            handler);
        rawUnavailable = null;
      }
      buildCapabilities(streams);
      manager.openCamera(
          cameraId,
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
              opening = false;
              if (closed || token != generation) {
                camera.close();
                return;
              }
              device = camera;
              createSession(token);
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
              camera.close();
              if (token != generation) return;
              device = null;
              opening = false;
              postError("Camera disconnected. Return to the app to reconnect.");
            }

            @Override
            public void onError(CameraDevice camera, int error) {
              camera.close();
              if (token != generation) return;
              device = null;
              opening = false;
              postError(
                  "Camera open failed (" + error + "). Close other camera apps and try again.");
            }
          },
          handler);
    } catch (Exception e) {
      opening = false;
      postError("Camera setup failed: " + e.getMessage());
    }
  }

  private String pickCamera() throws Exception {
    String fallback = null;
    for (String id : manager.getCameraIdList()) {
      CameraCharacteristics c = manager.getCameraCharacteristics(id);
      Integer facing = c.get(CameraCharacteristics.LENS_FACING);
      if (facing != null
          && facing
              == (front
                  ? CameraCharacteristics.LENS_FACING_FRONT
                  : CameraCharacteristics.LENS_FACING_BACK)) {
        if ("0".equals(id) && !front) return id;
        if (fallback == null) fallback = id;
      }
    }
    if (fallback == null) throw new Exception("Requested camera is unavailable");
    return fallback;
  }

  private static Size chooseJpeg(Size[] sizes, boolean maximum) throws Exception {
    if (sizes == null || sizes.length == 0)
      throw new Exception("JPEG still capture is not exposed");
    // Bound JPEG decode memory in v0.1. Do not claim access to stock-camera 200 MP modes.
    long cap = maximum ? 24_000_000L : 16_000_000L;
    Size best = null, smallest = sizes[0];
    for (Size s : sizes) {
      long area = (long) s.getWidth() * s.getHeight();
      if (area < (long) smallest.getWidth() * smallest.getHeight()) smallest = s;
      if (area <= cap && (best == null || area > (long) best.getWidth() * best.getHeight()))
        best = s;
    }
    if (best == null && (long) smallest.getWidth() * smallest.getHeight() > 24_000_000L)
      throw new Exception(
          "Only oversized JPEG streams are exposed; this beta caps capture at 24 MP for memory"
              + " safety");
    return best == null ? smallest : best;
  }

  private static Size choosePreview(Size[] sizes, Size photo) throws Exception {
    if (sizes == null || sizes.length == 0) throw new Exception("Preview unavailable");
    double desired = (double) photo.getWidth() / photo.getHeight(), score = Double.MAX_VALUE;
    Size best = sizes[0];
    for (Size s : sizes) {
      if (s.getWidth() > 1920 || s.getHeight() > 1920) continue;
      double next =
          Math.abs((double) s.getWidth() / s.getHeight() - desired) * 100
              + Math.abs((long) s.getWidth() * s.getHeight() - 1_600_000) / 1_600_000.0;
      if (next < score) {
        score = next;
        best = s;
      }
    }
    return best;
  }

  @SuppressWarnings("deprecation")
  private void createSession(int token) {
    try {
      SurfaceTexture surface = texture.getSurfaceTexture();
      if (surface == null) throw new Exception("Preview surface is not ready");
      surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
      previewSurface = new Surface(surface);
      List<Surface> outputs = new ArrayList<>(Arrays.asList(previewSurface, jpegReader.getSurface()));
      if (rawReader != null) outputs.add(rawReader.getSurface());
      device.createCaptureSession(
          outputs,
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
              if (closed || token != generation) {
                s.close();
                return;
              }
              session = s;
              try {
                repeat();
                main.post(
                    () -> {
                      if (!closed && token == generation) {
                        updateTransform(current.zoom);
                        listener.ready(capabilities, uprightWidth(), uprightHeight());
                      }
                    });
              } catch (Exception e) {
                postError("Preview request failed: " + e.getMessage());
              }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
              if (token != generation) return;
              if (rawReader != null) {
                // Keep the camera usable: retry without the RAW stream.
                rawReader.close();
                rawReader = null;
                rawUnavailable = "The phone rejected preview + JPEG + RAW in one session";
                try {
                  capabilities.put("rawImplemented", false).put("rawUnavailable", rawUnavailable);
                } catch (JSONException ignored) {
                }
                if (previewSurface != null) previewSurface.release();
                createSession(token);
                return;
              }
              postError(
                  "The phone rejected preview + JPEG at this size. Turn off Larger capture and"
                      + " retry.");
            }
          },
          handler);
    } catch (Exception e) {
      postError("Could not start photo session: " + e.getMessage());
    }
  }

  public void update(Snapshot settings) {
    current = settings;
    updateTransform(settings.zoom);
    Handler h = handler;
    if (h != null && !closed)
      h.post(
          () -> {
            if (session != null && !inFlight)
              try {
                repeat();
              } catch (Exception e) {
                postError("Camera settings rejected: " + e.getMessage());
              }
          });
  }

  private CaptureRequest.Builder request(int template, Snapshot s, boolean still) throws Exception {
    CaptureRequest.Builder b = device.createCaptureRequest(template);
    applyRecipe(b, s, still);
    return b;
  }

  private void repeat() throws Exception {
    CaptureRequest.Builder b = request(CameraDevice.TEMPLATE_PREVIEW, current, false);
    b.addTarget(previewSurface);
    session.setRepeatingRequest(
        b.build(),
        new CameraCaptureSession.CaptureCallback() {
          @Override
          public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
            if (!safeMode) {
              safeMode = true;
              main.post(
                  () ->
                      listener.status(
                          "ISP request failed. Compatibility mode active; check Diagnostics."));
              try {
                repeat();
              } catch (Exception e) {
                postError(e.getMessage());
              }
            }
          }
        },
        handler);
  }

  private JSONObject applyRecipe(CaptureRequest.Builder b, Snapshot s, boolean still)
      throws Exception {
    warnings.clear();
    JSONObject applied = new JSONObject();
    Recipe recipe = Recipe.ALL[s.recipe];
    if (recipe.raw && still) return applyRaw(b, s, recipe);
    // RAW recipes preview through the phone's normal ISP; the look is applied in development.
    boolean plainPreview = (neutral || recipe.raw) && !still;
    set(b, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
    set(b, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
    set(
        b,
        CaptureRequest.CONTROL_AF_MODE,
        chooseMode(
            chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            CaptureRequest.CONTROL_AF_MODE_OFF));
    Range<Integer> range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
    Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
    int index = 0;
    if (range != null && step != null && step.doubleValue() > 0)
      index =
          Math.max(
              range.getLower(),
              Math.min(
                  range.getUpper(),
                  (int)
                      Math.round(
                          (s.ev + (plainPreview ? 0 : recipe.exposureBias)) / step.doubleValue())));
    set(b, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index);
    applied.put("evIndex", index).put("userEv", s.ev).put("recipeEvBias", recipe.exposureBias);
    int wb =
        chooseMode(
            chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
            plainPreview ? CaptureRequest.CONTROL_AWB_MODE_AUTO : recipe.whiteBalance,
            CaptureRequest.CONTROL_AWB_MODE_AUTO);
    set(b, CaptureRequest.CONTROL_AWB_MODE, wb);
    applied.put("awbMode", wb);
    if (wb != recipe.whiteBalance)
      warnings.add("Recipe white-balance preset unavailable; AUTO requested instead");
    if (!safeMode && !plainPreview) {
      int edge =
          chooseMode(
              chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
              recipe.edgeMode,
              CaptureRequest.EDGE_MODE_FAST);
      if (set(b, CaptureRequest.EDGE_MODE, edge)) applied.put("edgeMode", edge);
      if (edge != recipe.edgeMode)
        warnings.add("Preferred sharpening mode unavailable; supported fallback requested");
      int noise =
          chooseMode(
              chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
              recipe.noiseMode,
              CaptureRequest.NOISE_REDUCTION_MODE_FAST,
              CaptureRequest.NOISE_REDUCTION_MODE_OFF);
      if (set(b, CaptureRequest.NOISE_REDUCTION_MODE, noise))
        applied.put("noiseReductionMode", noise);
      if (noise != recipe.noiseMode)
        warnings.add("Preferred noise-reduction mode unavailable; supported fallback requested");
      int[] tone = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
      Integer max = chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
      if (contains(tone, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
          && max != null
          && max >= 2
          && requestKeys.contains(CaptureRequest.TONEMAP_CURVE)) {
        TonemapCurve curve = recipe.curve(s.strength, Math.min(32, max));
        boolean ok =
            set(b, CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                && set(b, CaptureRequest.TONEMAP_CURVE, curve);
        if (ok) {
          applied.put("tonemapMode", CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
          applied.put("tonemapCurve", Recipe.curveJson(curve));
        }
      } else
        warnings.add(
            "Custom tone curves not exposed; recipe color response is unavailable. No JPEG filter"
                + " substituted.");
    } else if (safeMode)
      warnings.add(
          "Compatibility mode: custom tone/edge/noise requests omitted after a capture failure.");
    if (still) {
      set(b, CaptureRequest.JPEG_QUALITY, (byte) 97);
      set(b, CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
    }
    // No video FPS lock, shutter-angle conversion, HLG, P010, or video stabilization requests.
    applied.put("warnings", new JSONArray(warnings));
    applied.put("compatibilityMode", safeMode);
    return applied;
  }

  /** RAW capture request: phone auto exposure/WB/focus, lens-shading map, reference JPEG. */
  private JSONObject applyRaw(CaptureRequest.Builder b, Snapshot s, Recipe recipe) throws Exception {
    warnings.clear();
    JSONObject applied = new JSONObject();
    set(b, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
    set(b, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
    set(
        b,
        CaptureRequest.CONTROL_AF_MODE,
        chooseMode(
            chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            CaptureRequest.CONTROL_AF_MODE_OFF));
    set(b, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
    Range<Integer> range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
    Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
    int index = 0;
    // The user's EV is applied in development (metering key), so RAW capture only adds headroom
    // for the film's (possibly tuned) Dynamic Range setting.
    float bias = s.fuji != null ? Recipe.rawCaptureBias(s.fuji) : recipe.exposureBias;
    if (range != null && step != null && step.doubleValue() > 0)
      index =
          Math.max(
              range.getLower(),
              Math.min(range.getUpper(), (int) Math.round(bias / step.doubleValue())));
    set(b, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index);
    applied.put("evIndex", index).put("userEvAppliedInDevelopment", s.ev)
        .put("captureEvBias", bias);
    int[] shadingModes =
        chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES);
    if (contains(shadingModes, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        && set(
            b,
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON))
      applied.put("lensShadingMap", "requested");
    else warnings.add("Lens shading map not available; vignetting is not corrected in RAW development");
    set(b, CaptureRequest.JPEG_QUALITY, (byte) 95);
    set(b, CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
    applied.put("warnings", new JSONArray(warnings));
    applied.put("compatibilityMode", safeMode);
    return applied;
  }

  private <T> boolean set(CaptureRequest.Builder b, CaptureRequest.Key<T> key, T value) {
    if (!requestKeys.contains(key)) {
      warnings.add(key.getName() + " unavailable");
      return false;
    }
    try {
      b.set(key, value);
      return true;
    } catch (IllegalArgumentException e) {
      warnings.add(key.getName() + " rejected: " + e.getMessage());
      return false;
    }
  }

  public void capture(Snapshot snapshot) {
    Handler h = handler;
    if (h == null || closed) {
      postError("Camera is not ready");
      return;
    }
    h.post(
        () -> {
          if (session == null || device == null || inFlight) {
            postError("Camera is busy or not ready");
            return;
          }
          boolean wantsRaw = Recipe.ALL[snapshot.recipe].raw;
          if (wantsRaw && rawReader == null) {
            postError("RAW capture is unavailable: " + rawUnavailable);
            return;
          }
          inFlight = true;
          pending = snapshot;
          pendingRotation = deviceRotation;
          pendingBytes = null;
          pendingResult = null;
          pendingWantsRaw = wantsRaw;
          try {
            CaptureRequest.Builder b =
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            pendingApplied = applyRecipe(b, snapshot, true);
            b.addTarget(jpegReader.getSurface());
            if (wantsRaw) b.addTarget(rawReader.getSurface());
            b.setTag(snapshot);
            session.capture(
                b.build(),
                new CameraCaptureSession.CaptureCallback() {
                  @Override
                  public void onCaptureCompleted(
                      CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    if (inFlight && pending == snapshot) {
                      pendingResult = result;
                      tryFinish();
                    }
                  }

                  @Override
                  public void onCaptureFailed(
                      CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    if (pending != snapshot) return;
                    failCapture(
                        "Photo request failed (reason "
                            + failure.getReason()
                            + "). Check Diagnostics; try another recipe or restart the camera.");
                  }
                },
                handler);
            Snapshot tag = snapshot;
            handler.postDelayed(
                () -> {
                  if (inFlight && pending == tag)
                    failCapture(
                        "Photo capture timed out. No success was reported; retry the shot.");
                },
                12000);
          } catch (Exception e) {
            failCapture("Capture failed: " + e.getMessage());
          }
        });
  }

  private void tryFinish() {
    if (!inFlight || pendingBytes == null || pendingResult == null) return;
    if (pendingWantsRaw && pendingRaw == null) return;
    Long timestamp = pendingResult.get(CaptureResult.SENSOR_TIMESTAMP);
    if (timestamp == null || timestamp != pendingImageTime) return;
    if (pendingWantsRaw && timestamp != pendingRawTime) return;
    try {
      JSONObject receipt =
          new JSONObject()
              .put("schema", 1)
              .put("cameraId", cameraId)
              .put("device", Build.MODEL)
              .put("android", Build.VERSION.RELEASE)
              .put("recipe", Recipe.ALL[pending.recipe].json(pending.strength, pending.fuji))
              .put("requested", pendingApplied)
              .put("sourceFormat", "Camera2 ISP JPEG")
              .put("raw", false)
              .put("opticalZoom", false)
              .put("sensorTimestampNs", timestamp)
              .put("deviceRotation", pendingRotation)
              .put("extraRotation", extraRotation())
              .put("actual", resultJson(pendingResult));
      JSONObject actual = receipt.getJSONObject("actual");
      int afState = actual.optInt("afState", -1);
      if (afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
          || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
        receipt.put("focusWarning", "Camera reported not focused at capture. Tap the subject and retry.");
      }
      receipt.put(
          "verification",
          "CaptureResult metadata is evidence of reported settings, not proof of exact pixel"
              + " response. Device scene tests required.");
      receipt.put("evReported", pendingResult.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION));
      if (pendingApplied.has("tonemapMode")
          && actual.optInt("tonemapMode", -1) != pendingApplied.optInt("tonemapMode"))
        receipt.put(
            "ispWarning",
            "HAL did not report the requested tone-map mode. Recipe response is unverified.");
      byte[] bytes = pendingBytes;
      Snapshot snap = pending;
      if (pendingWantsRaw) {
        Image raw = pendingRaw;
        pendingRaw = null;
        RawFrame frame;
        File dng = null;
        int orientation = jpegOrientation();
        try {
          frame = RawSupport.frame(raw, chars, pendingResult);
          File dir = dngDirectory;
          if (dir != null) {
            dng = new File(dir, "capture-" + timestamp + ".dng");
            try {
              RawSupport.writeDng(
                  chars, pendingResult, raw, (orientation + extraRotation()) % 360, dng);
            } catch (Exception e) {
              if (!dng.delete() && dng.exists()) Log.w("Grain", "Partial DNG kept");
              dng = null;
              receipt.put("dngWarning", "DNG not written: " + e.getMessage());
            }
          }
          receipt.put("rawFrame", RawSupport.describe(frame, pendingResult));
        } finally {
          raw.close();
        }
        receipt
            .put("raw", true)
            .put("sourceFormat", "Camera2 RAW_SENSOR developed on device; phone ISP JPEG kept as reference")
            .put("rawSize", frame.width + "x" + frame.height);
        RawCapture capture = new RawCapture(frame, bytes, dng, orientation);
        clearPending();
        main.post(() -> listener.capturedRaw(capture, snap, receipt));
        return;
      }
      clearPending();
      main.post(() -> listener.captured(bytes, snap, receipt));
    } catch (Exception e) {
      failCapture("Could not construct capture receipt: " + e.getMessage());
    }
  }

  private static JSONObject resultJson(TotalCaptureResult r) throws Exception {
    JSONObject j = new JSONObject();
    put(j, "iso", r.get(CaptureResult.SENSOR_SENSITIVITY));
    put(j, "exposureNs", r.get(CaptureResult.SENSOR_EXPOSURE_TIME));
    put(j, "awbMode", r.get(CaptureResult.CONTROL_AWB_MODE));
    put(j, "awbState", r.get(CaptureResult.CONTROL_AWB_STATE));
    put(j, "aeState", r.get(CaptureResult.CONTROL_AE_STATE));
    put(j, "afState", r.get(CaptureResult.CONTROL_AF_STATE));
    put(j, "tonemapMode", r.get(CaptureResult.TONEMAP_MODE));
    put(j, "edgeMode", r.get(CaptureResult.EDGE_MODE));
    put(j, "noiseReductionMode", r.get(CaptureResult.NOISE_REDUCTION_MODE));
    TonemapCurve curve = r.get(CaptureResult.TONEMAP_CURVE);
    if (curve != null && Recipe.hasPoints(curve)) j.put("tonemapCurve", Recipe.curveJson(curve));
    return j;
  }

  private void failCapture(String reason) {
    clearPending();
    postError(reason);
  }

  private void clearPending() {
    inFlight = false;
    pendingWantsRaw = false;
    if (pendingRaw != null) {
      pendingRaw.close();
      pendingRaw = null;
    }
    pending = null;
    pendingBytes = null;
    pendingResult = null;
    pendingApplied = null;
  }

  public void focus(double sourceX, double sourceY) {
    Handler h = handler;
    if (h == null || closed) return;
    h.post(
        () -> {
          if (session == null || inFlight) return;
          try {
            Integer max = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (max == null || max == 0 || active == null) {
              main.post(() -> listener.status("Tap focus regions are not exposed by this camera"));
              return;
            }
            int cx = active.left + (int) (sourceX * active.width()),
                cy = active.top + (int) (sourceY * active.height()),
                half = Math.max(24, Math.min(active.width(), active.height()) / 20);
            Rect box =
                new Rect(
                    Math.max(active.left, cx - half),
                    Math.max(active.top, cy - half),
                    Math.min(active.right, cx + half),
                    Math.min(active.bottom, cy + half));
            CaptureRequest.Builder b = request(CameraDevice.TEMPLATE_PREVIEW, current, false);
            b.addTarget(previewSurface);
            set(b, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            set(
                b,
                CaptureRequest.CONTROL_AF_REGIONS,
                new MeteringRectangle[] {new MeteringRectangle(box, 1000)});
            set(b, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(b.build(), null, handler);
            set(b, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(b.build(), null, handler);
            handler.postDelayed(
                () -> {
                  if (!closed && !inFlight && session != null)
                    try {
                      repeat();
                    } catch (Exception ignored) {
                    }
                },
                2000);
          } catch (Exception e) {
            postError("Focus request failed: " + e.getMessage());
          }
        });
  }

  public void updateTransform(double zoom) {
    if (previewSize == null || chars == null
        || texture.getWidth() == 0 || texture.getHeight() == 0) return;
    int width=texture.getWidth(),height=texture.getHeight();
    // TextureView already handles SENSOR_ORIENTATION. JPEG orientation is a different pipeline.
    float[] source={0,0,width,0,width,height,0,height};
    float[] destination=PreviewMath.destinationCorners(width,height,previewSize.getWidth(),
        previewSize.getHeight(),sensorRotation,displayDegrees(),zoom);
    Matrix transform=new Matrix();transform.setPolyToPoly(source,0,destination,0,4);
    texture.setTransform(transform);
  }

  private int displayDegrees() {
    return switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_90 -> 90;
      case Surface.ROTATION_180 -> 180;
      case Surface.ROTATION_270 -> 270;
      default -> 0;
    };
  }

  public int uprightWidth() {
    return sensorRotation % 180 == 0 ? jpegSize.getWidth() : jpegSize.getHeight();
  }

  public int uprightHeight() {
    return sensorRotation % 180 == 0 ? jpegSize.getHeight() : jpegSize.getWidth();
  }

  /**
   * The UI is portrait-locked, so pictures are formed upright for a portrait-held phone. When the
   * phone is turned, the saved files get this extra clockwise rotation in their EXIF/DNG orientation.
   */
  public void setDeviceRotation(int degrees) {
    deviceRotation = ((degrees % 360) + 360) % 360;
  }

  private int extraRotation() {
    return front ? (360 - pendingRotation) % 360 : pendingRotation;
  }

  private int jpegOrientation() {
    int degrees = displayDegrees();
    return (sensorRotation + (front ? degrees : -degrees) + 360) % 360;
  }

  private void buildCapabilities(StreamConfigurationMap streams) throws Exception {
    JSONArray outputs = new JSONArray();
    for (Size s : streams.getOutputSizes(ImageFormat.JPEG))
      outputs.put(s.getWidth() + "x" + s.getHeight());
    JSONArray keys = new JSONArray();
    for (CaptureRequest.Key<?> key : requestKeys) keys.put(key.getName());
    capabilities =
        new JSONObject()
            .put("device", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("cameraId", cameraId)
            .put("sensorOrientation", sensorRotation)
            .put("previewOrientationStrategy", "TextureView sensor correction + display-only transform v2")
            .put("displayDegrees", displayDegrees())
            .put("selectedJpeg", jpegSize.toString())
            .put("preview", previewSize.toString())
            .put("jpegSizes", outputs)
            .put("requestKeys", keys)
            .put("recipeValidation", "NOT YET CALIBRATED on this device/firmware");
    put(
        capabilities,
        "hardwareLevel",
        chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
    capabilities
        .put("capabilities", ints(chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)))
        .put("awbModes", ints(chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)))
        .put(
            "toneMapModes", ints(chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)))
        .put("edgeModes", ints(chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)))
        .put(
            "noiseReductionModes",
            ints(chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)));
    put(capabilities, "maxCurvePoints", chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS));
    Size[] heif = null;
    try {
      heif = streams.getOutputSizes(ImageFormat.HEIC);
    } catch (IllegalArgumentException ignored) {
    }
    capabilities
        .put("heifAdvertised", heif != null && heif.length > 0)
        .put("heifImplemented", false)
        .put("rawImplemented", rawReader != null)
        .put("rawUnavailable", rawUnavailable == null ? JSONObject.NULL : rawUnavailable)
        .put("rawSelected", rawSize == null ? JSONObject.NULL : rawSize.toString())
        .put("rawProbe", RawSupport.probe(chars, streams))
        .put("captureLimit", "16 MP balanced / 24 MP larger, selected from public JPEG sizes");
  }

  public JSONObject capabilities() {
    return capabilities;
  }

  public boolean rawAvailable() {
    return rawReader != null;
  }

  public String rawUnavailableReason() {
    return rawUnavailable;
  }

  /** Where RAW captures write their DNG before the photo is saved; null = no DNG. */
  public void setDngDirectory(File dir) {
    dngDirectory = dir;
  }

  public void neutralPreview(boolean enabled) {
    neutral = enabled;
    Handler h = handler;
    if (h != null && !closed)
      h.post(
          () -> {
            if (session != null && !inFlight)
              try {
                repeat();
              } catch (Exception e) {
                postError("Preview comparison failed: " + e.getMessage());
              }
          });
  }

  public void close() {
    if (closing) return;
    closed = true;
    generation++;
    opening = false;
    closing = true;
    Handler h = handler;
    HandlerThread t = thread;
    handler = null;
    thread = null;
    if (h != null)
      h.post(
          () -> {
            clearPending();
            if (session != null) {
              session.close();
              session = null;
            }
            if (device != null) {
              device.close();
              device = null;
            }
            if (jpegReader != null) {
              jpegReader.close();
              jpegReader = null;
            }
            if (rawReader != null) {
              rawReader.close();
              rawReader = null;
            }
            if (previewSurface != null) {
              previewSurface.release();
              previewSurface = null;
            }
            if (t != null) t.quitSafely();
            closing = false;
          });
    else closing = false;
  }

  private void postError(String text) {
    main.post(() -> listener.error(text));
  }

  private static boolean contains(int[] a, int value) {
    if (a != null) for (int v : a) if (v == value) return true;
    return false;
  }

  private static int chooseMode(int[] available, int... preference) {
    for (int p : preference) if (contains(available, p)) return p;
    return available != null && available.length > 0
        ? available[0]
        : preference[preference.length - 1];
  }

  private static JSONArray ints(int[] values) {
    JSONArray a = new JSONArray();
    if (values != null) for (int i : values) a.put(i);
    return a;
  }

  private static void put(JSONObject json, String key, Object value) throws Exception {
    if (value != null) json.put(key, value);
  }
}
