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

    /** The preview's live exposure and focus state, a few times a second (main thread). */
    void meter(Meter meter);
  }

  /** What the phone's auto exposure and autofocus are doing in the preview right now. */
  public static final class Meter {
    public final long exposureNs;
    public final int iso;
    /** Exposure compensation the preview runs at, in EV (the user's EV for RAW films). */
    public final double compensationEv;
    public final int afState, aeState;
    public final boolean aeLocked;
    /** Latest lens focus distance reported by the HAL (>= 0), or -1 when unreported. */
    public final float focusDistance;
    /** Upright normalized [l, t, r, b] box of the actively tracked face in AF_FACE mode, or null. */
    public final double[] faceUpright;
    /** True scene neutral (ns × ISO) preserved when preview is driven in manual AE_MODE_OFF. */
    public final double neutralOverrideNsIso;

    Meter(
        long exposureNs,
        int iso,
        double compensationEv,
        int afState,
        int aeState,
        boolean aeLocked,
        float focusDistance,
        double[] faceUpright) {
      this(
          exposureNs,
          iso,
          compensationEv,
          afState,
          aeState,
          aeLocked,
          focusDistance,
          faceUpright,
          0.0);
    }

    Meter(
        long exposureNs,
        int iso,
        double compensationEv,
        int afState,
        int aeState,
        boolean aeLocked,
        float focusDistance,
        double[] faceUpright,
        double neutralOverrideNsIso) {
      this.exposureNs = exposureNs;
      this.iso = iso;
      this.compensationEv = compensationEv;
      this.afState = afState;
      this.aeState = aeState;
      this.aeLocked = aeLocked;
      this.focusDistance = focusDistance;
      this.faceUpright = faceUpright;
      this.neutralOverrideNsIso = neutralOverrideNsIso;
    }

    /** Exposure (ns × ISO) the meter asks for at 0 EV. */
    public double neutralNsIso() {
      if (neutralOverrideNsIso > 1.0) return neutralOverrideNsIso;
      return exposureNs * (double) iso / Math.pow(2, compensationEv);
    }
  }

  public static final class Snapshot {
    public final int recipe, strength;
    public final double zoom;
    public final String ratio;
    public final boolean keepFull;
    public final float ev;
    /** The RAW film as tuned in the app (null for ISP looks): what capture and development use. */
    public final FujiRecipe fuji;
    /** Manual exposure: shutter time in ns and ISO, 0 = automatic. */
    public final long shutterNs;
    public final int iso;
    /** Flash mode ({@link ExposureMath#FLASH_OFF} .. {@link ExposureMath#FLASH_TORCH}). */
    public final int flashMode;

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
      this(recipe, strength, zoom, ratio, keepFull, ev, fuji, 0, 0, ExposureMath.FLASH_OFF);
    }

    public Snapshot(
        int recipe,
        int strength,
        double zoom,
        String ratio,
        boolean keepFull,
        float ev,
        FujiRecipe fuji,
        long shutterNs,
        int iso) {
      this(recipe, strength, zoom, ratio, keepFull, ev, fuji, shutterNs, iso, ExposureMath.FLASH_OFF);
    }

    public Snapshot(
        int recipe,
        int strength,
        double zoom,
        String ratio,
        boolean keepFull,
        float ev,
        FujiRecipe fuji,
        long shutterNs,
        int iso,
        int flashMode) {
      this.shutterNs = Math.max(0, shutterNs);
      this.iso = Math.max(0, iso);
      this.flashMode = Math.max(ExposureMath.FLASH_OFF, Math.min(ExposureMath.FLASH_TORCH, flashMode));
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
    /** The JPEG orientation requested for the capture (the phone JPEG is tagged or turned by it). */
    public final int orientationDegrees;
    /** Clockwise turn that makes the sensor image upright for the scene (includes device rotation). */
    public final int uprightDegrees;

    RawCapture(RawFrame frame, byte[] phoneJpeg, File dng, int orientationDegrees, int uprightDegrees) {
      this.frame = frame;
      this.phoneJpeg = phoneJpeg;
      this.dng = dng;
      this.orientationDegrees = orientationDegrees;
      this.uprightDegrees = uprightDegrees;
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
  /** Face exposure: the camera's face detector runs for RAW films so development can meter faces. */
  private volatile boolean faceDetection = true;
  private volatile boolean faceLandmarks;

  /**
   * AF area modes (v0.5.0): where the lens looks for focus. SINGLE is a point you tap; MULTI is a
   * grid across the frame; ZONE is a larger box around the point; CENTER is the middle; FACE follows
   * the camera's face detector. The region goes on every request, so continuous AF searches only
   * inside it, and a one-shot focus triggers on it.
   */
  public static final int AF_SINGLE = 0, AF_MULTI = 1, AF_ZONE = 2, AF_CENTER = 3, AF_FACE = 4;
  private volatile int afArea = AF_SINGLE;
  private volatile boolean afLocked;
  /**
   * Focus mode: {@link FocusMath#FOCUS_AFC} (continuous AF with live face tracking),
   * {@link FocusMath#FOCUS_AFS} (single-shot AF), or {@link FocusMath#FOCUS_MANUAL} (direct
   * {@code LENS_FOCUS_DISTANCE} between 0 = infinity and {@code LENS_INFO_MINIMUM_FOCUS_DISTANCE}).
   */
  private volatile int focusMode = FocusMath.FOCUS_AFC;
  /** 0 = infinity, 1 = the lens' closest focus. */
  private volatile float focusFraction = 1f;
  /** The lens' closest focus distance as the HAL reports it; 0 when it reports none. */
  private volatile float minFocusDistance;
  /** Latest preview-reported lens focus distance and movement state. */
  private volatile float lastFocusDistance = -1f;
  private volatile int lastLensState = -1;
  /** Active focus-bracketing override (>= 0 while a focus-bracket step is active). */
  private volatile float bracketFraction = -1f;
  private volatile int bracketIndex, bracketTotal;
  private volatile boolean bracketAeHold;
  /** The touch point, normalized to the active array (0..1). */
  private float afX = .5f, afY = .5f;
  /** The last face the preview reported, in active-array coordinates, plus AF-C tracking state. */
  private final RectF faceBox = new RectF();
  private float[] trackedFaceNorm, submittedFaceNorm, lastPostedFaceNorm;
  private int trackedFaceId = -1;
  private long lastFaceSeenMs, lastFaceRepeatMs;
  /** AE-L: the preview's auto exposure holds its values; stills keep them (plus the film's headroom). */
  private volatile boolean aeLock;
  /** Until then stills keep the lens where a focus request put it (half-pressed shutter, tap). */
  private volatile long afHoldUntil;
  private volatile Meter meter;
  private long lastMeterPost;
  private int lastAfState = -2;
  private volatile int lastAeState = -1;
  private volatile boolean precaptureSawStart;
  private volatile double sceneNeutralNsIso = ExposureMath.DEFAULT_NEUTRAL_NS_ISO;
  private volatile double lastAppliedPreviewNsIso;
  private boolean manualSensor;
  private volatile boolean flashAvailable;
  private final CameraCaptureSession.CaptureCallback previewCallback =
      new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(
            CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
          Long time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
          Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
          Integer comp = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
          Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
          Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
          Integer reqAe = r.get(CaptureRequest.CONTROL_AE_MODE);
          boolean manualAe = reqAe != null && reqAe == CaptureRequest.CONTROL_AE_MODE_OFF;
          lastAeState = ae == null ? -1 : ae;
          if (lastAeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) precaptureSawStart = true;
          Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
          if (fd != null && fd >= 0f) lastFocusDistance = fd;
          Integer ls = result.get(CaptureResult.LENS_STATE);
          lastLensState = ls == null ? -1 : ls;
          if (afArea == AF_FACE && bracketFraction < 0f) trackFace(result);
          else if (afArea != AF_FACE && trackedFaceNorm != null) clearTrackedFace();
          double[] faceUpright =
              afArea == AF_FACE && trackedFaceNorm != null
                  ? FocusMath.sensorBoxToUpright(
                      trackedFaceNorm[0],
                      trackedFaceNorm[1],
                      trackedFaceNorm[2],
                      trackedFaceNorm[3],
                      sensorRotation,
                      front)
                  : null;
          boolean faceUiChanged = FocusMath.movedEnough(lastPostedFaceNorm, trackedFaceNorm, 0.012f);
          CameraCharacteristics c = chars;
          Rational step = c == null ? null : c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
          double compEv = comp == null || step == null ? 0 : comp * step.doubleValue();
          if (!manualAe && time != null && time > 0 && iso != null && iso > 0) {
            double hwNeutral = (time * (double) iso) / Math.pow(2.0, compEv);
            if (Double.isFinite(hwNeutral) && hwNeutral > 1.0) sceneNeutralNsIso = hwNeutral;
          }
          Meter m =
              new Meter(
                  time == null ? 0 : time,
                  iso == null ? 0 : iso,
                  compEv,
                  af == null ? -1 : af,
                  lastAeState,
                  aeLock,
                  lastFocusDistance,
                  faceUpright,
                  manualAe ? sceneNeutralNsIso : 0.0);
          meter = m;
          long now = SystemClock.uptimeMillis();
          if (now - lastMeterPost >= 160 || m.afState != lastAfState || faceUiChanged) {
            lastMeterPost = now;
            lastAfState = m.afState;
            lastPostedFaceNorm = trackedFaceNorm == null ? null : trackedFaceNorm.clone();
            main.post(() -> listener.meter(m));
          }
        }

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
      };

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
                probeFaceLandmarks();
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
    session.setRepeatingRequest(b.build(), previewCallback, handler);
  }

  /** AE-L on or off; call {@link #update} afterwards so the preview follows. */
  public void setAeLock(boolean on) {
    aeLock = on;
  }

  /** True when the camera takes manual shutter speed and ISO (Camera2 MANUAL_SENSOR). */
  public boolean manualExposureAvailable() {
    return manualSensor;
  }

  /** True when this camera unit reports a physical flash / LED torch. */
  public boolean flashAvailable() {
    return flashAvailable;
  }

  /**
   * Feeds center-weighted linear luminance of the live preview back into the scene meter while
   * manual or priority exposure is active ({@code CONTROL_AE_MODE_OFF}), keeping Shutter Priority,
   * ISO Priority, and the Manual EV bar responsive to lighting changes.
   */
  public void reportPreviewLuma(double meanLinearY) {
    Snapshot s = current;
    CameraCharacteristics c = chars;
    if (!manualSensor || c == null || s == null || (s.shutterNs <= 0 && s.iso <= 0)) return;
    double applied = lastAppliedPreviewNsIso;
    if (applied <= 1.0 || meanLinearY < 0.0) return;
    Range<Long> times = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
    Range<Integer> isos = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
    double minNsIso =
        times != null && isos != null ? times.getLower() * (double) isos.getLower() : 10_000.0;
    double maxNsIso =
        times != null && isos != null ? times.getUpper() * (double) isos.getUpper() : 1e14;
    double prev = sceneNeutralNsIso;
    double next = ExposureMath.refineSceneNeutral(prev, applied, meanLinearY, minNsIso, maxNsIso);
    sceneNeutralNsIso = next;
    double logDelta = Math.abs(Math.log(next / Math.max(1.0, prev)));
    Meter prevMeter = meter;
    if (prevMeter != null && logDelta >= 0.05) {
      Meter updated =
          new Meter(
              prevMeter.exposureNs,
              prevMeter.iso,
              prevMeter.compensationEv,
              prevMeter.afState,
              prevMeter.aeState,
              prevMeter.aeLocked,
              prevMeter.focusDistance,
              prevMeter.faceUpright,
              next);
      meter = updated;
      main.post(() -> listener.meter(updated));
    }
    boolean priorityMode = (s.shutterNs > 0) != (s.iso > 0);
    if (priorityMode && logDelta >= 0.09) {
      Handler h = handler;
      if (h != null && !closed) {
        h.post(
            () -> {
              if (!closed && session != null && !inFlight) {
                try {
                  repeat();
                } catch (Exception ignored) {
                }
              }
            });
      }
    }
  }

  /** AF mode for a request: AF-C tracks continuously; AF-S and AF lock hold between triggers. */
  private int afMode(boolean still) {
    if (bracketFraction >= 0f || focusMode == FocusMath.FOCUS_MANUAL)
      return CaptureRequest.CONTROL_AF_MODE_OFF;
    if (focusMode == FocusMath.FOCUS_AFS
        || afLocked
        || (still && SystemClock.uptimeMillis() < afHoldUntil)) {
      return chooseMode(
          chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
          CaptureRequest.CONTROL_AF_MODE_AUTO,
          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
          CaptureRequest.CONTROL_AF_MODE_OFF);
    }
    return chooseMode(
        chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        CaptureRequest.CONTROL_AF_MODE_AUTO,
        CaptureRequest.CONTROL_AF_MODE_OFF);
  }

  /**
   * Applies Manual (M), Shutter Priority (S), or ISO Priority exposure on BOTH live preview and
   * still capture so turning the Shutter or ISO dial immediately updates the live viewfinder as
   * well as the captured photo. On still capture, ZSL is explicitly disabled and frame duration is
   * raised for slow shutter speeds so HALs never return a buffered auto-exposed preview frame.
   */
  private JSONObject manualExposure(
      CaptureRequest.Builder b, Snapshot s, double headroomEv, boolean still) throws Exception {
    if (s.shutterNs <= 0 && s.iso <= 0) {
      if (!still) lastAppliedPreviewNsIso = 0.0;
      return null;
    }
    Range<Long> times = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
    Range<Integer> isos = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
    if (!manualSensor
        || times == null
        || isos == null
        || !requestKeys.contains(CaptureRequest.SENSOR_EXPOSURE_TIME)
        || !requestKeys.contains(CaptureRequest.SENSOR_SENSITIVITY)) {
      if (!still) lastAppliedPreviewNsIso = 0.0;
      warnings.add("Manual exposure unavailable on this camera; automatic exposure used");
      return new JSONObject().put("mode", "unavailable");
    }
    Meter m = meter;
    double neutral =
        m != null && m.exposureNs > 0 && m.iso > 0 ? m.neutralNsIso() : sceneNeutralNsIso;
    ExposureMath.Plan plan =
        ExposureMath.planStill(
            s.shutterNs,
            s.iso,
            s.ev,
            still ? headroomEv : 0.0,
            neutral,
            times.getLower(),
            times.getUpper(),
            isos.getLower(),
            isos.getUpper());
    if (plan == null) {
      if (!still) lastAppliedPreviewNsIso = 0.0;
      return null;
    }
    long requestTime;
    int requestIso;
    long frameDurationNs;
    if (still) {
      requestTime = plan.shutterNs;
      requestIso = plan.captureIso;
      frameDurationNs = Math.max(33_333_333L, requestTime);
      if (requestKeys.contains(CaptureRequest.CONTROL_ENABLE_ZSL)) {
        set(b, CaptureRequest.CONTROL_ENABLE_ZSL, false);
      }
    } else {
      long[] pv =
          ExposureMath.planPreview(
              plan.shutterNs,
              plan.iso,
              times.getLower(),
              times.getUpper(),
              isos.getLower(),
              isos.getUpper());
      requestTime = pv[0];
      requestIso = (int) pv[1];
      frameDurationNs = pv[2];
      lastAppliedPreviewNsIso = requestTime * (double) requestIso;
    }
    set(b, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
    set(b, CaptureRequest.SENSOR_EXPOSURE_TIME, requestTime);
    set(b, CaptureRequest.SENSOR_SENSITIVITY, requestIso);
    if (requestKeys.contains(CaptureRequest.SENSOR_FRAME_DURATION)) {
      set(b, CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs);
    }
    return new JSONObject()
        .put("mode", plan.mode)
        .put("shutterNs", plan.shutterNs)
        .put("iso", plan.iso)
        .put("captureIso", plan.captureIso)
        .put("requestShutterNs", requestTime)
        .put("requestIso", requestIso)
        .put("headroomEv", still ? headroomEv : 0.0)
        .put("meterNeutralNsIso", plan.neutralNsIso)
        .put("developEv", plan.developEv);
  }

  /**
   * Configures Camera2 flash & AE mode for Off, Auto, Fill On, and continuous Torch across both
   * Program Auto ({@code CONTROL_AE_MODE_ON_*}) and Manual ({@code CONTROL_AE_MODE_OFF}).
   */
  private void applyFlash(
      CaptureRequest.Builder b,
      Snapshot s,
      boolean still,
      boolean manualActive,
      JSONObject applied)
      throws Exception {
    int fm = flashAvailable ? s.flashMode : ExposureMath.FLASH_OFF;
    applied.put("flashMode", ExposureMath.FLASH_RECEIPT[fm]);
    if (!flashAvailable && s.flashMode != ExposureMath.FLASH_OFF) {
      warnings.add("Flash requested, but this camera unit reports no flash hardware");
    }
    if (fm == ExposureMath.FLASH_OFF) {
      if (requestKeys.contains(CaptureRequest.FLASH_MODE)) {
        set(b, CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
      }
      return;
    }
    if (fm == ExposureMath.FLASH_TORCH) {
      if (still && requestKeys.contains(CaptureRequest.CONTROL_ENABLE_ZSL)) {
        set(b, CaptureRequest.CONTROL_ENABLE_ZSL, false);
      }
      if (requestKeys.contains(CaptureRequest.FLASH_MODE)) {
        set(b, CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
      }
      return;
    }
    // FLASH_AUTO or FLASH_ON
    if (still && requestKeys.contains(CaptureRequest.CONTROL_ENABLE_ZSL)) {
      set(b, CaptureRequest.CONTROL_ENABLE_ZSL, false);
    }
    if (manualActive) {
      boolean fireSingle =
          still
              && (fm == ExposureMath.FLASH_ON
                  || (fm == ExposureMath.FLASH_AUTO
                      && sceneNeutralNsIso > 33_333_333.0 * 400.0));
      if (requestKeys.contains(CaptureRequest.FLASH_MODE)) {
        set(
            b,
            CaptureRequest.FLASH_MODE,
            fireSingle ? CaptureRequest.FLASH_MODE_SINGLE : CaptureRequest.FLASH_MODE_OFF);
      }
      return;
    }
    int wantAe =
        fm == ExposureMath.FLASH_ON
            ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            : CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
    int chosenAe =
        chooseMode(
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
            wantAe,
            CaptureRequest.CONTROL_AE_MODE_ON);
    set(b, CaptureRequest.CONTROL_AE_MODE, chosenAe);
    if (requestKeys.contains(CaptureRequest.FLASH_MODE)) {
      boolean fallbackSingle =
          still && chosenAe == CaptureRequest.CONTROL_AE_MODE_ON && fm == ExposureMath.FLASH_ON;
      set(
          b,
          CaptureRequest.FLASH_MODE,
          fallbackSingle ? CaptureRequest.FLASH_MODE_SINGLE : CaptureRequest.FLASH_MODE_OFF);
    }
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
    set(b, CaptureRequest.CONTROL_AF_MODE, afMode(still));
    applyAfArea(b, applied);
    applyManualFocus(b, applied);
    JSONObject manual = (!still && neutral) ? null : manualExposure(b, s, 0, still);
    boolean manualActive = manual != null && !"unavailable".equals(manual.optString("mode"));
    if (manual != null) applied.put("manual", manual);
    if (!manualActive && (aeLock || bracketAeHold)) {
      set(b, CaptureRequest.CONTROL_AE_LOCK, true);
      applied.put("aeLock", true);
    }
    applyFlash(b, s, still, manualActive, applied);
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
    // RAW films with a fixed white balance preview with the nearest Camera2 preset (development
    // uses the exact white point); hold-to-compare shows the phone's auto white balance.
    int wantWb =
        neutral && !still
            ? CaptureRequest.CONTROL_AWB_MODE_AUTO
            : recipe.raw
                ? Recipe.awbModeFor(s.fuji != null ? s.fuji : recipe.fuji)
                : plainPreview ? CaptureRequest.CONTROL_AWB_MODE_AUTO : recipe.whiteBalance;
    int wb =
        chooseMode(
            chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
            wantWb,
            CaptureRequest.CONTROL_AWB_MODE_AUTO);
    set(b, CaptureRequest.CONTROL_AWB_MODE, wb);
    applied.put("awbMode", wb);
    if (wb != wantWb)
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
    if (recipe.raw || afArea == AF_FACE) applyFaceDetection(b, applied);
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
    set(b, CaptureRequest.CONTROL_AF_MODE, afMode(true));
    applyAfArea(b, applied);
    applyManualFocus(b, applied);
    // Auto films keep the phone's auto white balance (development uses its as-shot white); a fixed
    // Fujifilm white balance requests the nearest Camera2 preset for the reference JPEG and the
    // DNG's as-shot white, while development uses the exact white point.
    int wantWb = Recipe.awbModeFor(s.fuji != null ? s.fuji : recipe.fuji);
    int wb =
        chooseMode(
            chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
            wantWb,
            CaptureRequest.CONTROL_AWB_MODE_AUTO);
    set(b, CaptureRequest.CONTROL_AWB_MODE, wb);
    applied.put("awbMode", wb);
    if (wb != wantWb)
      warnings.add("White-balance preset unavailable for the reference JPEG; AUTO requested instead");
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
    JSONObject manual = manualExposure(b, s, bias, true);
    boolean manualActive = manual != null && !"unavailable".equals(manual.optString("mode"));
    if (manual != null) applied.put("manual", manual);
    if (!manualActive && (aeLock || bracketAeHold)) {
      set(b, CaptureRequest.CONTROL_AE_LOCK, true);
      applied.put("aeLock", true);
    }
    applyFlash(b, s, true, manualActive, applied);
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
    applyFaceDetection(b, applied);
    applied.put("warnings", new JSONArray(warnings));
    applied.put("compatibilityMode", safeMode);
    return applied;
  }

  /**
   * Face detection: asks the camera's face detector to report faces for RAW face exposure and/or
   * AF-C face tracking ({@code AF_FACE}). Off in compatibility mode or when neither feature wants it.
   */
  private void applyFaceDetection(CaptureRequest.Builder b, JSONObject applied) throws Exception {
    if (!faceDetection && afArea != AF_FACE) {
      applied.put("faceDetectMode", "off (setting)");
      return;
    }
    if (safeMode) {
      applied.put("faceDetectMode", "off (compatibility mode)");
      return;
    }
    int[] modes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
    // Eye AF and the focus probe need the landmarks, and only FULL provides them; SIMPLE is the
    // faster default for face exposure.
    int mode =
        faceLandmarks
            ? (contains(modes, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL)
                ? CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
                : contains(modes, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
                    ? CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                    : CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
            : (contains(modes, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
                ? CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                : contains(modes, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL)
                    ? CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
                    : CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF);
    if (mode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
        || !requestKeys.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE)) {
      applied.put("faceDetectMode", "unavailable (faces are searched in the phone JPEG)");
      return;
    }
    if (set(b, CaptureRequest.STATISTICS_FACE_DETECT_MODE, mode)) applied.put("faceDetectMode", mode);
  }

  /** Face exposure on or off; call {@link #update} afterwards so the preview follows. */
  public void setFaceDetection(boolean on) {
    faceDetection = on;
  }

  /**
   * FULL face detection (face ids and eye / mouth landmarks) instead of SIMPLE. Off by default:
   * SIMPLE is faster and face exposure does not need the landmarks. Needed for the focus probe and
   * for eye AF; the receipt reports which mode was used and whether landmarks arrived.
   */
  public void setFaceLandmarks(boolean on) {
    faceLandmarks = on;
  }

  /** The AF area mode; see the AF_* constants. */
  public void setAfArea(int area) {
    afArea = area;
  }

  public int afArea() {
    return afArea;
  }

  /** Sets the focus mode ({@link FocusMath#FOCUS_AFC}, {@link FocusMath#FOCUS_AFS}, {@link FocusMath#FOCUS_MANUAL}). */
  public void setFocusMode(int mode) {
    focusMode = Math.max(0, Math.min(2, mode));
  }

  public int focusMode() {
    return focusMode;
  }

  /** Turns manual focus on: the lens is driven from {@link #setFocusFraction} and the autofocus off. */
  public void setManualFocus(boolean on) {
    focusMode = on ? FocusMath.FOCUS_MANUAL : FocusMath.FOCUS_AFC;
  }

  public boolean manualFocus() {
    return focusMode == FocusMath.FOCUS_MANUAL;
  }

  /** True when the camera supports direct focus-distance stepping for Focus Bracketing. */
  public boolean focusBracketingAvailable() {
    return manualSensor
        && minFocusDistance > 0f
        && requestKeys.contains(CaptureRequest.LENS_FOCUS_DISTANCE);
  }

  /** Starting focus fraction in [0..1] (0 = infinity, 1 = closest focus) for Focus Bracketing. */
  public float currentFocusFraction() {
    if (focusMode == FocusMath.FOCUS_MANUAL) return focusFraction;
    if (minFocusDistance > 0f && lastFocusDistance >= 0f) {
      return Math.max(0f, Math.min(1f, lastFocusDistance / minFocusDistance));
    }
    return 0.5f;
  }

  /**
   * Positions the physical lens on the preview stream for step {@code index} of a Focus Bracket,
   * waits briefly until the VCM settles (or the settle cap elapses), and invokes {@code onSettled}
   * on the main thread so the still is captured at the requested focus plane.
   */
  public void prepareBracketStep(float fraction, int index, int total, Runnable onSettled) {
    Handler h = handler;
    if (h == null || closed) {
      postError("Camera is not ready for focus bracketing");
      return;
    }
    int token = generation;
    h.post(
        () -> {
          if (closed || token != generation || session == null) return;
          bracketFraction = Math.max(0f, Math.min(1f, fraction));
          bracketIndex = index;
          bracketTotal = total;
          bracketAeHold = index > 0 || aeLock;
          float targetDistance = bracketFraction * minFocusDistance;
          try {
            repeat();
          } catch (Exception e) {
            postError("Focus bracket step failed: " + e.getMessage());
            return;
          }
          long startMs = SystemClock.uptimeMillis();
          Runnable waitLens =
              new Runnable() {
                @Override
                public void run() {
                  if (closed || token != generation || session == null || bracketFraction < 0f) return;
                  long elapsed = SystemClock.uptimeMillis() - startMs;
                  boolean stationary =
                      lastLensState == CaptureResult.LENS_STATE_STATIONARY
                          || (lastFocusDistance >= 0f
                              && Math.abs(lastFocusDistance - targetDistance)
                                  <= Math.max(0.15f, minFocusDistance * 0.025f));
                  if ((elapsed >= 90 && stationary) || elapsed >= 210) {
                    main.post(onSettled);
                  } else {
                    h.postDelayed(this, 35);
                  }
                }
              };
          h.postDelayed(waitLens, 90);
        });
  }

  /** Ends a Focus Bracketing burst and restores normal preview AF/AE. */
  public void clearBracket() {
    bracketFraction = -1f;
    bracketIndex = 0;
    bracketTotal = 0;
    bracketAeHold = false;
    Handler h = handler;
    if (h != null && !closed) {
      h.post(
          () -> {
            if (!closed && session != null && !inFlight) {
              try {
                repeat();
              } catch (Exception ignored) {
              }
            }
          });
    }
  }

  /** The focus position as a fraction: 0 is infinity, 1 is the lens' closest focus. */
  public void setFocusFraction(float fraction) {
    focusFraction = Math.max(0, Math.min(1, fraction));
  }

  public float focusFraction() {
    return focusFraction;
  }

  /** The closest the lens goes, or 0 when the camera does not report one. */
  public float minFocusDistance() {
    return minFocusDistance;
  }

  /** Locks the AF region and the lens where they are, until the next focus. */
  public void setAfLock(boolean on) {
    afLocked = on;
  }

  public boolean afLocked() {
    return afLocked;
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
          int token = generation;
          boolean manualActive =
              manualSensor && (snapshot.shutterNs > 0 || snapshot.iso > 0);
          boolean needPrecapture =
              flashAvailable
                  && !manualActive
                  && !aeLock
                  && !bracketAeHold
                  && requestKeys.contains(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER)
                  && (snapshot.flashMode == ExposureMath.FLASH_ON
                      || (snapshot.flashMode == ExposureMath.FLASH_AUTO
                          && (lastAeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                              || sceneNeutralNsIso > 33_333_333.0 * 350.0)));
          if (needPrecapture) {
            try {
              precaptureSawStart = false;
              CaptureRequest.Builder pre =
                  request(CameraDevice.TEMPLATE_PREVIEW, snapshot, false);
              pre.addTarget(previewSurface);
              set(
                  pre,
                  CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                  CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
              session.capture(pre.build(), previewCallback, handler);
              long startMs = SystemClock.uptimeMillis();
              Runnable pollPrecapture =
                  new Runnable() {
                    @Override
                    public void run() {
                      if (closed || token != generation || !inFlight || pending != snapshot) return;
                      long elapsed = SystemClock.uptimeMillis() - startMs;
                      int ae = lastAeState;
                      boolean settled =
                          (precaptureSawStart && ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE)
                              || (elapsed >= 180
                                  && (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                                      || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                                      || ae == CaptureResult.CONTROL_AE_STATE_LOCKED));
                      if (settled || elapsed >= 850) {
                        submitStillCapture(snapshot, wantsRaw);
                      } else {
                        handler.postDelayed(this, 40);
                      }
                    }
                  };
              handler.postDelayed(pollPrecapture, 60);
              return;
            } catch (Exception ignored) {
              // Fall through to direct still capture if precapture request fails on a quirk HAL.
            }
          }
          submitStillCapture(snapshot, wantsRaw);
        });
  }

  private void submitStillCapture(Snapshot snapshot, boolean wantsRaw) {
    if (session == null || device == null || !inFlight || pending != snapshot) return;
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
        RawCapture capture =
            new RawCapture(frame, bytes, dng, orientation, (orientation + extraRotation()) % 360);
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
    put(j, "flashState", r.get(CaptureResult.FLASH_STATE));
    put(j, "focusDistance", r.get(CaptureResult.LENS_FOCUS_DISTANCE));
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
    focus(sourceX, sourceY, false);
  }

  /**
   * Focuses on a point. With hold (a half-pressed shutter) the lens stays there until
   * {@link #releaseFocus}; otherwise continuous autofocus resumes after two seconds.
   */
  public void focus(double sourceX, double sourceY, boolean hold) {
    afX = (float) sourceX;
    afY = (float) sourceY;
    if (focusMode == FocusMath.FOCUS_MANUAL || afLocked) return;
    afHoldUntil = hold ? Long.MAX_VALUE : SystemClock.uptimeMillis() + 2000;
    Handler h = handler;
    if (h == null || closed) return;
    h.post(
        () -> {
          if (session == null || inFlight) return;
          try {
            MeteringRectangle[] regions = afRegions();
            if (regions == null) {
              main.post(() -> listener.status("Tap focus regions are not exposed by this camera"));
              return;
            }
            CaptureRequest.Builder b = request(CameraDevice.TEMPLATE_PREVIEW, current, false);
            b.addTarget(previewSurface);
            set(b, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            set(b, CaptureRequest.CONTROL_AF_REGIONS, regions);
            set(b, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(b.build(), null, handler);
            set(b, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(b.build(), previewCallback, handler);
            if (!hold)
              handler.postDelayed(
                  () -> {
                    if (!closed && !inFlight && session != null
                        && SystemClock.uptimeMillis() >= afHoldUntil)
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

  /** Ends a held focus: stills taken in the next moment keep it, then continuous AF resumes. */
  public void releaseFocus() {
    if (afHoldUntil != Long.MAX_VALUE) return;
    afHoldUntil = SystemClock.uptimeMillis() + 700;
    Handler h = handler;
    if (h != null && !closed)
      h.postDelayed(
          () -> {
            if (!closed && !inFlight && session != null && SystemClock.uptimeMillis() >= afHoldUntil)
              try {
                repeat();
              } catch (Exception ignored) {
              }
          },
          750);
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

  private void clearTrackedFace() {
    trackedFaceNorm = null;
    submittedFaceNorm = null;
    trackedFaceId = -1;
    faceBox.setEmpty();
  }

  /**
   * AF-C face tracking: follows the subject's face across preview frames using sticky proximity/ID
   * matching and exponential smoothing, and re-submits the repeating preview request whenever the
   * face is acquired, moves significantly, or leaves the frame after a short grace window.
   */
  private void trackFace(TotalCaptureResult result) {
    Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
    Rect active =
        chars == null ? null : chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
    if (active == null || active.width() <= 0 || active.height() <= 0) return;
    long now = SystemClock.uptimeMillis();
    int rawCount = faces == null ? 0 : faces.length;
    float[] boxes = new float[rawCount * 4];
    int[] ids = new int[rawCount];
    int valid = 0;
    float aw = active.width(), ah = active.height();
    for (int i = 0; i < rawCount; i++) {
      Face f = faces[i];
      if (f == null || f.getBounds() == null) continue;
      Rect r = f.getBounds();
      if (r.width() <= 0 || r.height() <= 0) continue;
      int pad = Math.max(1, Math.min(r.width(), r.height()) / 10);
      float nl = Math.max(0f, (r.left - pad - active.left) / aw);
      float nt = Math.max(0f, (r.top - pad - active.top) / ah);
      float nr = Math.min(1f, (r.right + pad - active.left) / aw);
      float nb = Math.min(1f, (r.bottom + pad - active.top) / ah);
      if (nr <= nl || nb <= nt) continue;
      boxes[valid * 4] = nl;
      boxes[valid * 4 + 1] = nt;
      boxes[valid * 4 + 2] = nr;
      boxes[valid * 4 + 3] = nb;
      ids[valid] = f.getId() == Face.ID_UNSUPPORTED ? -1 : f.getId();
      valid++;
    }
    boolean hasPrev = trackedFaceNorm != null && (now - lastFaceSeenMs) <= 650;
    float prevCx = hasPrev ? (trackedFaceNorm[0] + trackedFaceNorm[2]) * 0.5f : 0.5f;
    float prevCy = hasPrev ? (trackedFaceNorm[1] + trackedFaceNorm[3]) * 0.5f : 0.5f;
    int picked =
        FocusMath.pickTrackedFace(boxes, valid, ids, trackedFaceId, prevCx, prevCy, hasPrev);
    if (picked >= 0) {
      float[] next = {
        boxes[picked * 4], boxes[picked * 4 + 1], boxes[picked * 4 + 2], boxes[picked * 4 + 3]
      };
      trackedFaceNorm = FocusMath.smoothBox(hasPrev ? trackedFaceNorm : null, next, 0.48f);
      trackedFaceId = ids[picked];
      lastFaceSeenMs = now;
      faceBox.set(
          active.left + trackedFaceNorm[0] * aw,
          active.top + trackedFaceNorm[1] * ah,
          active.left + trackedFaceNorm[2] * aw,
          active.top + trackedFaceNorm[3] * ah);
      if (focusMode == FocusMath.FOCUS_AFC
          && !afLocked
          && !inFlight
          && session != null
          && now >= afHoldUntil) {
        boolean shouldRepeat =
            submittedFaceNorm == null
                || (now - lastFaceRepeatMs >= 180
                    && FocusMath.movedEnough(submittedFaceNorm, trackedFaceNorm, 0.025f));
        if (shouldRepeat) {
          submittedFaceNorm = trackedFaceNorm.clone();
          lastFaceRepeatMs = now;
          try {
            repeat();
          } catch (Exception ignored) {
          }
        }
      }
    } else if (trackedFaceNorm != null && (now - lastFaceSeenMs) > 650) {
      boolean hadSubmitted = submittedFaceNorm != null;
      clearTrackedFace();
      if (hadSubmitted
          && focusMode == FocusMath.FOCUS_AFC
          && !afLocked
          && !inFlight
          && session != null
          && now >= afHoldUntil) {
        lastFaceRepeatMs = now;
        try {
          repeat();
        } catch (Exception ignored) {
        }
      }
    }
  }

  /**
   * Positions the lens directly when manual focus or focus bracketing is active; otherwise records
   * the autofocus mode (AF-C / AF-S) and face-tracking status for the capture receipt.
   */
  private void applyManualFocus(CaptureRequest.Builder b, JSONObject applied) throws Exception {
    if (bracketFraction >= 0f) {
      if (!manualSensor || minFocusDistance <= 0f) {
        warnings.add("Focus bracketing is unavailable on this camera");
        return;
      }
      double distance = Math.max(0, Math.min(1, bracketFraction)) * minFocusDistance;
      if (set(b, CaptureRequest.LENS_FOCUS_DISTANCE, (float) distance)) {
        applied.put("focusMode", "focus-bracket");
        applied.put("focusDistance", distance);
        applied.put(
            "focusBracket",
            new JSONObject()
                .put("frame", bracketIndex + 1)
                .put("of", bracketTotal)
                .put("focusFraction", bracketFraction)
                .put("focusDistance", distance));
      } else warnings.add("The lens focus distance was rejected by this camera");
      return;
    }
    if (focusMode != FocusMath.FOCUS_MANUAL) {
      applied.put("focusMode", focusMode == FocusMath.FOCUS_AFS ? "af-s" : "af-c");
      if (afArea == AF_FACE) applied.put("faceTracked", trackedFaceNorm != null);
      return;
    }
    if (!manualSensor) {
      warnings.add("This camera does not advertise MANUAL_SENSOR; manual focus is unavailable");
      return;
    }
    if (minFocusDistance <= 0) {
      warnings.add("This camera reports no closest focus distance; manual focus is unavailable");
      return;
    }
    double distance = Math.max(0, Math.min(1, focusFraction)) * minFocusDistance;
    if (set(b, CaptureRequest.LENS_FOCUS_DISTANCE, (float) distance)) {
      applied.put("focusMode", "manual");
      applied.put("focusDistance", distance);
    } else warnings.add("The lens focus distance was rejected by this camera");
  }

  /** Puts the current AF area on the request, and records it for the receipt. */
  private void applyAfArea(CaptureRequest.Builder b, JSONObject applied) throws Exception {
    MeteringRectangle[] regions = afRegions();
    if (regions == null) {
      if (afArea != AF_SINGLE)
        warnings.add("This camera exposes no AF regions; the AF area control is unavailable");
      return;
    }
    if (set(b, CaptureRequest.CONTROL_AF_REGIONS, regions)) {
      applied.put("afArea", afAreaName()).put("afRegions", regions.length);
      if (afLocked) applied.put("afLock", true);
    } else warnings.add("AF regions were rejected by this camera");
  }

  private String afAreaName() {
    return switch (afArea) {
      default -> "single";
      case AF_MULTI -> "multi";
      case AF_ZONE -> "zone";
      case AF_CENTER -> "center";
      case AF_FACE -> "face";
    };
  }

  /**
   * The metering regions for the current AF area, in active-array coordinates. Null when this HAL
   * exposes no AF regions at all, in which case nothing is set and the receipt says why.
   */
  private MeteringRectangle[] afRegions() {
    Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
    Integer max = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
    if (active == null || active.width() <= 0 || active.height() <= 0) return null;
    if (max == null || max <= 0) return null;
    int w = active.width(), h = active.height();
    int cx = active.left + (int) (afX * w), cy = active.top + (int) (afY * h);
    int box = Math.max(24, Math.min(w, h) / 20);
    int zone = Math.max(box, Math.min(w, h) / 4);
    if (afArea == AF_MULTI) {
      int count = Math.min(max, 9), cols = (int) Math.ceil(Math.sqrt(count));
      int rows = Math.max(1, (int) Math.ceil((double) count / cols));
      List<MeteringRectangle> list = new ArrayList<>();
      for (int r = 0; r < rows && list.size() < count; r++)
        for (int c = 0; c < cols && list.size() < count; c++) {
          int l = active.left + c * w / cols, t = active.top + r * h / rows;
          int right = active.left + (c + 1) * w / cols, b = active.top + (r + 1) * h / rows;
          int overlap = Math.max(1, Math.min(right - l, b - t) / 8);
          list.add(
              new MeteringRectangle(
                  Math.max(active.left, l - overlap / 2), Math.max(active.top, t - overlap / 2),
                  Math.min(active.right, right + overlap / 2),
                  Math.min(active.bottom, b + overlap / 2),
                  MeteringRectangle.METERING_WEIGHT_MAX / count));
        }
      return list.toArray(new MeteringRectangle[0]);
    }
    if (afArea == AF_ZONE) {
      return new MeteringRectangle[] {
        new MeteringRectangle(
            Math.max(active.left, cx - zone), Math.max(active.top, cy - zone),
            Math.min(active.right, cx + zone), Math.min(active.bottom, cy + zone),
            MeteringRectangle.METERING_WEIGHT_MAX - 1)
      };
    }
    if (afArea == AF_FACE && !faceBox.isEmpty()) {
      float fcx = faceBox.centerX(), fcy = faceBox.centerY();
      float hw = Math.max(box, faceBox.width() * 0.5f);
      float hh = Math.max(box, faceBox.height() * 0.5f);
      return new MeteringRectangle[] {
        new MeteringRectangle(
            Math.max(active.left, Math.round(fcx - hw)),
            Math.max(active.top, Math.round(fcy - hh)),
            Math.min(active.right, Math.round(fcx + hw)),
            Math.min(active.bottom, Math.round(fcy + hh)),
            MeteringRectangle.METERING_WEIGHT_MAX - 1)
      };
    }
    RectF r;
    if (afArea == AF_CENTER)
      r = new RectF(active.left + w * .5f, active.top + h * .5f, active.left + w * .5f,
          active.top + h * .5f);
    else r = new RectF(cx, cy, cx, cy);
    return new MeteringRectangle[] {
      new MeteringRectangle(
          Math.max(active.left, (int) r.left - box), Math.max(active.top, (int) r.top - box),
          Math.min(active.right, (int) r.right + box), Math.min(active.bottom, (int) r.bottom + box),
          MeteringRectangle.METERING_WEIGHT_MAX - 1)
    };
  }

  /**
   * Live part of the focus probe (v0.5.0 plan, step 0): one capture with FULL face detection, to
   * see whether this HAL accepts FULL and returns {@code STATISTICS_FACES} at all. With no face in
   * the frame it reports the empty result; whether the optional eye / mouth / id fields are actually
   * filled needs a face in frame, and that is reported per photo instead (see RawSupport.faces).
   * The outcome lands in {@code capabilities.focus.live}, shown in the Camera diagnostics panel.
   * A rejected probe must never disturb the preview.
   */
  private void probeFaceLandmarks() {
    if (session == null || inFlight || device == null || chars == null) return;
    int[] modes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
    if (modes == null || !contains(modes, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL)) return;
    if (!requestKeys.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE)) return;
    try {
      CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      b.addTarget(previewSurface);
      set(b, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
      set(b, CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL);
      session.capture(
          b.build(),
          new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(
                CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
              try {
                Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
                JSONObject o = new JSONObject();
                if (faces == null) {
                  o.put("faces", "key absent from the result");
                } else {
                  o.put("faces", faces.length);
                  int eyes = 0, ids = 0, mouths = 0;
                  for (Face f : faces) {
                    if (f == null) continue;
                    if (f.getLeftEyePosition() != null && f.getRightEyePosition() != null) eyes++;
                    if (f.getId() != Face.ID_UNSUPPORTED) ids++;
                    if (f.getMouthPosition() != null) mouths++;
                  }
                  o.put("eyeLandmarks", eyes).put("ids", ids).put("mouthLandmarks", mouths);
                  if (faces.length == 0) o.put("note", "no face in frame at probe time");
                }
                JSONObject focus = capabilities.optJSONObject("focus");
                if (focus == null) focus = new JSONObject();
                focus.put("live", o);
                capabilities.put("focus", focus);
              } catch (Exception ignored) {
                // Keep whatever the diagnostics panel already shows.
              }
            }
          },
          handler);
    } catch (Exception ignored) {
    }
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
    manualSensor =
        contains(
            chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
    capabilities.put("manualSensor", manualSensor);
    Boolean flashInfo = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
    flashAvailable = flashInfo != null && flashInfo;
    capabilities.put("flashAvailable", flashAvailable);
    capabilities.put("aeModes", ints(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)));
    // Focus probe (v0.5.0 plan, step 0): what this HAL exposes for the AF area modes, eye AF and
    // manual focus. Static facts here; the live part (whether FULL face detection actually fills
    // the optional eye/mouth/id fields) is probeFaceLandmarks(), and the receipt reports landmark
    // presence per photo.
    JSONObject focus = new JSONObject();
    focus.put("afModes", ints(chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)));
    put(focus, "maxRegionsAf", chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF));
    put(focus, "maxRegionsAe", chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE));
    focus.put(
        "faceDetectModes",
        ints(chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)));
    put(focus, "maxFaces", chars.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT));
    // LENS_INFO_MINIMUM_FOCUS_DISTANCE is a plain Float, not a Range: the closest the lens goes.
    Float closest = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
    if (closest != null) {
      minFocusDistance = closest;
      put(focus, "minFocusDistance", closest);
    }
    JSONArray focusKeys = new JSONArray();
    for (CaptureRequest.Key<?> k :
        new CaptureRequest.Key<?>[] {
          CaptureRequest.CONTROL_AF_REGIONS,
          CaptureRequest.CONTROL_AE_REGIONS,
          CaptureRequest.LENS_FOCUS_DISTANCE,
          CaptureRequest.STATISTICS_FACE_DETECT_MODE,
          CaptureRequest.CONTROL_AF_TRIGGER})
      if (requestKeys.contains(k)) focusKeys.put(k.getName());
    focus.put("requestKeys", focusKeys);
    JSONArray faceResultKeys = new JSONArray();
    for (CaptureResult.Key<?> k :
        new CaptureResult.Key<?>[] {CaptureResult.STATISTICS_FACES})
      if (chars.getAvailableCaptureResultKeys().contains(k)) faceResultKeys.put(k.getName());
    focus.put("resultKeys", faceResultKeys);
    focus.put("manualSensor", manualSensor);
    capabilities.put("focus", focus);
    Range<Long> times = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
    if (times != null)
      capabilities.put(
          "exposureTimeRangeNs", new JSONArray().put(times.getLower()).put(times.getUpper()));
    Range<Integer> isos = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
    if (isos != null)
      capabilities.put("isoRange", new JSONArray().put(isos.getLower()).put(isos.getUpper()));
    float[] apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
    if (apertures != null && apertures.length > 0) capabilities.put("aperture", (double) apertures[0]);
    float[] focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
    SizeF sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
    if (focal != null && focal.length > 0) {
      capabilities.put("focalLengthMm", (double) focal[0]);
      if (sensor != null && sensor.getWidth() > 0)
        capabilities.put(
            "equivalentFocalMm",
            Math.round(
                focal[0] * 43.27
                    / Math.hypot(sensor.getWidth(), sensor.getHeight())));
    }
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
            bracketFraction = -1f;
            bracketAeHold = false;
            clearTrackedFace();
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
