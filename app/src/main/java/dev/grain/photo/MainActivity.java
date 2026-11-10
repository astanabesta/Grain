package dev.grain.photo;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaActionSound;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import dev.grain.photo.develop.AppleTune;
import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.HiResMerger;
import dev.grain.photo.develop.LutPreview;
import dev.grain.photo.develop.RawFrame;
import dev.grain.photo.develop.TuningControl;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Grain's single-camera-screen interaction design, implemented without Flutter or a WebView. */
public final class MainActivity extends ComponentActivity
    implements GrainCamera.Listener, CameraBack.Host {
  private static final int PAPER = 0xfff4f1e8,
      INK = 0xff303c29,
      MUTED = 0xff87917c,
      ORANGE = 0xffe76736,
      LINE = 0xffd6ddca;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private FrameLayout root, frame, ui;
  /** The camera back (dials, screen, buttons); turned with the phone inside {@link #ui}. */
  private CameraBack back;
  private LinearLayout panel;
  private TextureView texture;
  /** Status line: never shown itself, the camera's screen shows it while working. */
  private TextView status;
  /** The camera back's shutter button (shows the self-timer count). */
  private TextView shutter;
  /** What the camera's screen shows: camera, q (quick menu), menu or play. */
  private String mode = "camera";
  private int drive, dispMode, viewMode, finish, uiRotation, deviceRotation, afFeedback, isoSetting,
      flashMode, onboardingStep, onboardingStock = 1;
  private long shutterNs;
  private boolean evf, powerOn = true, aeLock, isoDial, evCustom, sounds = true, showHistogram = true,
      showLevel = true, autoRotate = true, halfPressed, halfClosedOverlay, neutralHeld;
  private float afX = .5f, afY = .5f, levelSmooth = Float.NaN, pitchSmooth = Float.NaN;
  /** Camera-axis tilt (front/back) for the Sony-style level: + means looking down, from gravity z. */
  private SensorManager sensors;
  private Sensor gravity;
  private final SensorEventListener pitchListener =
      new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
          float z = e.values[2], m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + z * z);
          if (m < 1) return;
          pitch(Math.max(-1, Math.min(1, z / m)));
        }

        @Override
        public void onAccuracyChanged(Sensor s, int accuracy) {}
      };
  private long[] shutterValues = SHUTTER_NS;
  private int[] isoValues = ISO_STEPS;
  private Recipe[] dialFilms = new Recipe[0];
  private Recipe[] bracketFilms;
  private GrainCamera.Meter lastMeter;
  private MediaActionSound sound;
  private Bitmap histogramBitmap;
  private int[] histogramPixels;
  private int batteryLevel = -1, frameCount = -1, histogramTicks;
  private int previewLeft = Integer.MIN_VALUE, previewTop, previewWidth, previewHeight;
  private int safeTop, safeBottom;
  private int qIndex, menuTab, menuIndex, playIndex;
  private final List<PhotoStore.Photo> playList = new ArrayList<>();
  private ImageView playImage;
  private TextView playInfo, playReceipt;
  private float playZoom = 1f, playPanX = 0f, playPanY = 0f;
  private final Matrix playMatrix = new Matrix();
  private String playBaseInfo = "";
  private final List<TextView[]> qTiles = new ArrayList<>();
  private TextView qName, qValue, qKelvinValue;
  private LinearLayout qKelvinRow, menuList;
  private ScrollView menuScroll;
  private TextView[] menuTabs;
  private TextView menuTitle;
  private static final long[] SHUTTER_NS = {
    250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L,
    66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L
  };
  private static final int[] ISO_STEPS = {
    50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200,
    4000, 5000, 6400, 8000, 10000, 12800
  };
  /** Drive-dial positions (delegated to {@link DriveModes}). */
  static final int DRIVE_SINGLE = DriveModes.DRIVE_SINGLE,
      DRIVE_TIMER_3 = DriveModes.DRIVE_TIMER_3,
      DRIVE_TIMER_5 = DriveModes.DRIVE_TIMER_5,
      DRIVE_FS_BKT = DriveModes.DRIVE_FS_BKT,
      DRIVE_FOCUS_BKT = DriveModes.DRIVE_FOCUS_BKT,
      DRIVE_HIRES_36 = DriveModes.DRIVE_HIRES_36,
      DRIVE_HIRES_48 = DriveModes.DRIVE_HIRES_48,
      DRIVE_HIRES_112 = DriveModes.DRIVE_HIRES_112;

  static final String[] DRIVES = DriveModes.DRIVES;
  private static final String[] VIEW_MODES = {"EYE SENSOR", "EVF ONLY", "LCD ONLY"};
  private static final String[] GRID_STYLES = {"Off", "Thirds", "Square", "Diag + square", "Golden ratio"};
  private static final String[] AF_NAMES = {"SINGLE", "MULTI", "ZONE", "CENTER", "FACE"};
  private static final String[] EV_LABELS = {
    "−2", "−1⅔", "−1⅓", "−1", "−⅔", "−⅓", "0", "+⅓", "+⅔", "+1", "+1⅓", "+1⅔", "+2", "C"
  };
  private static final int EV_C = 13;
  private ScrollView panelScroll;
  private GrainCamera camera;
  private OrientationEventListener orientationListener;
  /** Film-sheet thumbnails through each imported LUT, built once per import. */
  private final java.util.Map<String, Bitmap> lutPreviews = new java.util.HashMap<>();
  private volatile PhotoStore store;
  private SharedPreferences preferences;
  private JSONObject capabilities = new JSONObject();
  private int recipe, strengths[] = new int[Recipe.ALL.length], timerSeconds;
  private LutLibrary luts;
  /** Saved per-film tuning of the RAW films. */
  private RecipeTuning tuning;
  private ActivityResultLauncher<String[]> lutPicker;
  private ActivityResultLauncher<Intent> installPermissionLauncher;
  private boolean keepRawFiles = true;
  private Runnable afterLutImport;
  private String ratio = "3:4";
  private double zoom = 1;
  private float ev = 0;
  /** The AF area mode, a {@link GrainCamera} AF_* constant; see AF_AREA_NAMES. */
  private int afArea = GrainCamera.AF_SINGLE;
  /** The AF area modes this phone can really do, in menu order. See {@link #ready}. */
  private final List<Integer> afAreas = new ArrayList<>();
  /** Focus mode: {@link FocusMath#FOCUS_AFC}, {@link FocusMath#FOCUS_AFS}, or {@link FocusMath#FOCUS_MANUAL}. */
  private int focusMode = FocusMath.FOCUS_AFC;
  /** Manual focus: the lens is driven from a distance instead of by the autofocus. */
  private boolean mfOn;
  /** The manual-focus step; 0 is infinity, {@link #MF_STEPS} is the lens' closest focus. */
  private int mfStep = MF_STEPS / 2;
  /** Peaking: the preview's edges are drawn back over it, so focus is visible. */
  private boolean peaking;
  /** Focus Check magnifier mode: {@link FocusMath#MAG_OFF}..{@link FocusMath#MAG_4X_ON}. */
  private int magnifier = FocusMath.MAG_4X_HOLD;
  private boolean magHeld;
  private static final int MAG_SRC_W = 540, MAG_SRC_H = 720, MAG_OUT = 240;
  private Bitmap magSource, magPatch;
  private Canvas magCanvas;
  private final Matrix magMatrix = new Matrix();
  private final Matrix magTexMatrix = new Matrix();
  private final Paint magPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private int[] magPixels, magLuma;
  /** Focus Bracketing settings & active sequence state. */
  private int bktFramesIndex = 1, bktStepMode = FocusMath.BKT_STEP_MEDIUM;
  private float[] focusBracketPlan;
  private int focusBracketStep;
  private long focusBracketStartNs;
  /** Tripod 4-Frame High-Resolution burst state. */
  private boolean hiResActive, hiResSavedAeLock;
  private int hiResStep;
  /** The sub-pixel phase cells the Hi-Res frames have covered, and the last frame's shift. */
  private int hiResPhaseMask, hiResPhases;
  private float hiResShiftPx;
  /** The pixel count this run is actually merging toward (the mode's target, or what fits). */
  private long hiResTargetPixelsUsed;
  /** The drive mode this burst is running, so the merge size survives a mid-burst dial change. */
  private int hiResMode = DRIVE_HIRES_36;
  private long hiResStartNs;
  private HiResMerger.Accumulator hiResAccumulator;
  private File hiResAnchorDng;
  private byte[] hiResAnchorPhoneJpeg;
  /** In-app update state. */
  private boolean notifyUpdates = true, updateDownloading, updateFallbackTried;
  private String skippedUpdateTag = "", startupNotifiedTag = "";
  private long lastUpdateCheckMs;
  private AppUpdater.Release availableUpdate, pendingInstallRelease;
  private File lastDownloadedUpdateApk;
  /** Wi-Fi remote viewfinder sender (RemoteStream); OFF by default. */
  private RemoteStream remote;
  private String remoteToken = "";
  private boolean remoteOn;
  /** Viewfinder framing grid: 0 Off, 1 Thirds, 2 Square, 3 Diag + Square (Sony-style). */
  private int gridStyle;
  private boolean keepFull = true,
      faceExposure = true,
      faceLandmarks = false,
      stackOff = false,
      afLock,
      larger,
      front,
      busy,
      saving,
      ready,
      resumed,
      permissionAsked;
  private int sourceWidth = 3060, sourceHeight = 4080;
  private int countdownToken;
  private boolean counting;
  private boolean gallerySelectMode;
  private final java.util.Set<String> gallerySelectedIds = new java.util.LinkedHashSet<>();
  private final List<PhotoStore.Photo> pendingDelete = new ArrayList<>();
  private LinearLayout snackbar;
  private Runnable purgeTask;
  private String page = "camera";

  boolean startupFailed;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    CrashLog.install(this);
    preferences = getSharedPreferences("grain", MODE_PRIVATE);
    initUpdaterHooks();
    try {
      onCreateBody(saved);
    } catch (Throwable e) {
      // A failure while opening must not take Grain down silently, and onResume() must not run
      // against half-built fields afterwards. Keep the activity alive in minimal safe mode so the
      // report can be copied and the built-in updater can still replace a broken build.
      startupFailed = true;
      android.util.Log.e("Grain", "Startup failed", e);
      CrashLog.recordCaught(this, e);
      showStartupFailure(e);
    }
  }

  private void initUpdaterHooks() {
    installPermissionLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            res -> {
              AppUpdater.Release pending = pendingInstallRelease;
              pendingInstallRelease = null;
              if (pending != null && getPackageManager().canRequestPackageInstalls()) {
                startUpdateDownload(pending);
              }
            });
    AppUpdater.setConfirmationLauncher(
        confirmIntent -> {
          if (isDestroyed() || isFinishing() || !resumed) return false;
          try {
            startActivity(confirmIntent);
            return true;
          } catch (Exception e) {
            return false;
          }
        });
    AppUpdater.setStatusListener(
        (installStatus, msg) ->
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  if (installStatus == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    message("Tap Update in the system prompt to finish installing.");
                  } else if (installStatus == PackageInstaller.STATUS_SUCCESS) {
                    availableUpdate = null;
                    lastDownloadedUpdateApk = null;
                    pushOsd();
                    message("Grain updated successfully.");
                  } else if (AppUpdater.isSystemBlockedAbort(installStatus, msg)
                      || (installStatus != PackageInstaller.STATUS_FAILURE_ABORTED
                          && lastDownloadedUpdateApk != null
                          && lastDownloadedUpdateApk.isFile()
                          && !updateFallbackTried)) {
                    if (lastDownloadedUpdateApk != null
                        && lastDownloadedUpdateApk.isFile()
                        && !updateFallbackTried) {
                      updateFallbackTried = true;
                      try {
                        AppUpdater.launchSystemApkInstaller(this, lastDownloadedUpdateApk);
                        return;
                      } catch (Exception ignored) {
                      }
                    }
                    message(
                        "Update could not install"
                            + (msg != null && !msg.isEmpty()
                                ? ": " + msg
                                : " (matching signing key required)"));
                  } else if (installStatus == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    message("Update cancelled.");
                  } else {
                    message(
                        "Update could not install"
                            + (msg != null && !msg.isEmpty()
                                ? ": " + msg
                                : " (matching signing key required)"));
                  }
                }));
  }

  /** Shows a startup failure as a dialog the user can copy, instead of a crash. */
  private void showStartupFailure(Throwable error) {
    final String text = CrashLog.describe(error);
    try {
      new AlertDialog.Builder(this)
          .setTitle("Grain could not start")
          .setMessage("Something went wrong while opening:\n\n" + error)
          .setCancelable(false)
          .setPositiveButton(
              "Copy report",
              (d, w) -> {
                try {
                  ClipboardManager clip =
                      (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                  if (clip != null) clip.setPrimaryClip(ClipData.newPlainText("Grain crash", text));
                  Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                  // the clipboard is not worth crashing over
                }
              })
          .setNeutralButton("Check update", (d, w) -> checkForUpdates(true))
          .setNegativeButton("Close", (d, w) -> finish())
          .show();
    } catch (Throwable ignored) {
      // even the error dialog could not be shown
    }
  }

  private void onCreateBody(Bundle saved) {
    tuning = new RecipeTuning(preferences);
    Arrays.fill(strengths, 80);
    loadPrefs();
    store = new PhotoStore(this);
    store.purgeTrash();
    luts = new LutLibrary(this);
    lutPicker =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
              if (uri != null) importLuts(uri);
            });
    build();
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() { navigateBack(); }
    });
    camera = new GrainCamera(this, texture, this);
    camera.setFaceDetection(faceExposure);
    camera.setStackOff(stackOff);
    camera.setFaceLandmarks(faceLandmarks);
    camera.setAfArea(afArea);
    camera.setAfLock(afLock);
    camera.setFocusMode(focusMode);
    camera.setFocusFraction(mfStep / (float) MF_STEPS);
    sound = new MediaActionSound();
    sound.load(MediaActionSound.SHUTTER_CLICK);
    sound.load(MediaActionSound.FOCUS_COMPLETE);
    orientationListener =
        new OrientationEventListener(this) {
          int last = -1;

          @Override
          public void onOrientationChanged(int degrees) {
            if (degrees == ORIENTATION_UNKNOWN) {
              level(Float.NaN);
              return;
            }
            // Electronic level: the phone's tilt from the edge the camera back is drawn upright on.
            level(((degrees - uiRotation + 540) % 360) - 180);
            // 20-degree hysteresis around the diagonals so a slight tilt does not flip it.
            if (last >= 0) {
              int d = Math.abs(((degrees - last + 540) % 360) - 180);
              if (d < 65) return;
            }
            last = ((degrees + 45) / 90 * 90) % 360;
            deviceRotation = last;
            if (camera != null) camera.setDeviceRotation(last);
            applyUiRotation(last, true);
          }
        };
    applyRawFilesPreference();
    texture.setSurfaceTextureListener(
        new TextureView.SurfaceTextureListener() {
          @Override
          public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
            openCamera();
          }

          @Override
          public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
            camera.updateTransform(zoom);
          }

          @Override
          public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
            ready = false;
            camera.close();
            return true;
          }

          @Override
          public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    updateLabels();
    updateThumb();
    if (!preferences.getBoolean("onboardingDone", false)) {
      showOnboarding(0);
    }
  }

  private void build() {
    root = new FrameLayout(this);
    root.setBackgroundColor(0xff000000);
    setContentView(root);
    Window window = getWindow();
    WindowManager.LayoutParams attrs = window.getAttributes();
    attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    window.setAttributes(attrs);
    immersive();
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int top = 0, bottom = 0;
          DisplayCutout cutout = insets.getDisplayCutout();
          if (cutout != null) {
            top = cutout.getSafeInsetTop();
            bottom = cutout.getSafeInsetBottom();
          }
          if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            top = Math.max(top, bars.top);
            bottom = Math.max(bottom, bars.bottom);
          } else {
            top = Math.max(top, insets.getStableInsetTop());
            bottom = Math.max(bottom, insets.getStableInsetBottom());
          }
          safeTop = top;
          safeBottom = bottom;
          if (panel != null) panel.setPadding(0, safeTop, 0, safeBottom);
          return insets;
        });
    root.requestApplyInsets();
    // The preview sits in screen coordinates (never rotated) exactly under the camera back's screen.
    frame = new FrameLayout(this);
    frame.setBackgroundColor(0xff000000);
    texture = new TextureView(this);
    frame.addView(texture, new FrameLayout.LayoutParams(-1, -1));
    root.addView(frame, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.START));
    ui = new FrameLayout(this);
    root.addView(ui, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    back = new CameraBack(this, this);
    back.setMappingRoot(root);
    ui.addView(back, new FrameLayout.LayoutParams(-1, -1));
    shutter = back.shutter;
    status = new TextView(this);
    status.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence text, int start, int before, int count) {}

          @Override
          public void afterTextChanged(Editable text) {
            if (back == null) return;
            back.state.status = text.toString();
            back.state.busy = busy;
            back.lamp.set(busy ? 2 : counting ? 3 : 0);
            back.refresh();
          }
        });
    back.setFinish(finish);
    back.setIsoMode(isoDial);
    back.setFlashMode(flashMode);
    back.setPhotoRatio(Framing.portraitRatio(ratio));
    root.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          if (r - l != or - ol || b - t != ob - ot) root.post(() -> applyUiRotation(uiRotation, false));
        });
    buildFilmRow();
    updateDials();
    applyViewMode(false);
  }

  /** Full screen: the camera back owns every pixel; a swipe shows the system bars for a moment. */
  @SuppressWarnings("deprecation")
  private void immersive() {
    Window window = getWindow();
    if (Build.VERSION.SDK_INT >= 30) {
      window.setDecorFitsSystemWindows(false);
      WindowInsetsController controller = window.getInsetsController();
      if (controller != null) {
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      }
    } else
      window
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) immersive();
  }

  /**
   * Turns the camera back with the phone (the activity itself stays portrait, so capture, crop and
   * EXIF orientation are untouched). Upside down keeps the last orientation.
   */
  private void applyUiRotation(int degrees, boolean animate) {
    if (ui == null || root.getWidth() == 0) return;
    int deg = autoRotate ? degrees : 0;
    if (deg == 180) deg = uiRotation;
    boolean land = deg == 90 || deg == 270;
    int w = root.getWidth(), h = root.getHeight(), targetW = land ? h : w, targetH = land ? w : h;
    ViewGroup.LayoutParams now = ui.getLayoutParams();
    if (deg == uiRotation && now.width == targetW && now.height == targetH) return;
    final int finalDeg = deg;
    Runnable apply =
        () -> {
          uiRotation = finalDeg;
          ui.setLayoutParams(new FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER));
          ui.setRotation(-finalDeg);
          back.setLandscape(land);
          back.requestLayout();
          ui.post(back::reportPreview);
          pushOsd();
        };
    if (animate && ui.isLaidOut()) {
      ui.animate().cancel();
      ui.animate()
          .alpha(0)
          .setDuration(110)
          .withEndAction(
              () -> {
                apply.run();
                ui.animate().alpha(1).setDuration(170).start();
              })
          .start();
    } else apply.run();
  }

  /**
   * Plain Fujifilm film simulations appear once their LUT is imported; the ISP looks, the
   * published recipes and Sony s709 always show (a film without its LUT explains how to get it).
   */
  private boolean visible(Recipe f) {
    return !f.standard || f.fuji.sony() || (luts != null && luts.has(f.fuji.filmSimulation));
  }

  private void buildFilmRow() {
    if (back == null) return;
    if (!visible(Recipe.ALL[recipe])) recipe = 0;
    List<Recipe> list = new ArrayList<>();
    for (Recipe f : Recipe.DISPLAY_ORDER) if (visible(f)) list.add(f);
    dialFilms = list.toArray(new Recipe[0]);
    String[] labels = new String[dialFilms.length];
    boolean[] accent = new boolean[dialFilms.length];
    for (int i = 0; i < dialFilms.length; i++) {
      labels[i] = filmCode(dialFilms[i]);
      accent[i] = dialFilms[i].raw && !dialFilms[i].standard;
    }
    back.film.setItems(labels, accent, Math.max(0, indexOfFilm(recipe)));
  }

  private int indexOfFilm(int id) {
    for (int i = 0; i < dialFilms.length; i++) if (dialFilms[i].id == id) return i;
    return -1;
  }

  /** Short code on the film dial, Fujifilm style. */
  private static String filmCode(Recipe r) {
    switch (r.id) {
      case 0:
        return "DAY";
      case 1:
        return "CST";
      case 2:
        return "GLN";
      case 3:
        return "AFT";
      case 4:
        return "PTR";
      default:
        break;
    }
    if (r.fuji == null) return "ISP";
    if (r.fuji.sony()) return "s709";
    if (r.fuji.apple()) {
      dev.grain.photo.develop.AppleRender.Style st =
          dev.grain.photo.develop.AppleRender.Style.fromSimulation(r.fuji.filmSimulation);
      if (st != null) return st.dialCode;
    }
    if (r.fuji == FujiRecipe.REGGIES_SUPERIA) return "SUP";
    if (r.fuji == FujiRecipe.KODAK_GOLD_200) return "GLD";
    if (r.fuji == FujiRecipe.BRIGHT_KODAK) return "BKD";
    if (r.fuji == FujiRecipe.KODAK_800_V3) return "K800";
    if (r.fuji == FujiRecipe.LEICA_X) return "LCX";
    if (r.fuji == FujiRecipe.KODACHROME_64) return "K64";
    if (r.fuji == FujiRecipe.PACIFIC_BLUES) return "PAC";
    if (r.fuji == FujiRecipe.VIBRANT_ARIZONA) return "ARZ";
    if (r.fuji == FujiRecipe.CINESTILL_800T) return "800T";
    if (r.fuji == FujiRecipe.KODAK_TRI_X_400) return "TX";
    switch (r.fuji.filmSimulation) {
      case "PROVIA":
        return "STD";
      case "Velvia":
        return "V";
      case "ASTIA":
        return "S";
      case "CLASSIC-CHROME":
        return "CC";
      case "REALA-ACE":
        return "RA";
      case "PRO-Neg.Hi":
        return "NH";
      case "PRO-Neg.Std":
        return "NS";
      case "CLASSIC-Neg.":
        return "NC";
      case "NOSTALGIC-Neg.":
        return "NN";
      case "ETERNA":
        return "E";
      case "ETERNA-BB":
        return "EB";
      case "ACROS":
        return "A";
      default:
        return r.name.length() > 4 ? r.name.substring(0, 4).toUpperCase(Locale.ROOT) : r.name;
    }
  }

  /** The memo holder's box colours: background, ink, stripe. */
  private static int[] memoColors(Recipe r) {
    String code = filmCode(r);
    switch (code) {
      case "PTR":
        return new int[] {0xfff1e7d3, 0xff3a2a1c, 0xffd2691e};
      case "SUP":
        return new int[] {0xff2f8f4e, 0xfff4f1e8, 0xffd9d4c6};
      case "GLD":
        return new int[] {0xfff2b51c, 0xff4a2a10, 0xffc0392b};
      case "BKD":
        return new int[] {0xfff7cf3d, 0xff2b1c0e, 0xffd94126};
      case "K800":
        return new int[] {0xffe5a93b, 0xff2b1b12, 0xff9e2a2b};
      case "LCX":
        return new int[] {0xff1b1c1a, 0xfff4f1e8, 0xffd92b2b};
      case "K64":
        return new int[] {0xfff4c430, 0xff8b1a1a, 0xffb22222};
      case "PAC":
        return new int[] {0xff2d5f8b, 0xfff4f1e8, 0xffa8d0e6};
      case "ARZ":
        return new int[] {0xffc8693a, 0xfffff3e0, 0xff5c3317};
      case "800T":
        return new int[] {0xff1d2f3f, 0xffe8e0d0, 0xffd04030};
      case "TX":
        return new int[] {0xffe8c547, 0xff1a1a1a, 0xff1a1a1a};
      case "s709":
        return new int[] {0xff151515, 0xffe8e8e8, 0xffe86a2d};
      case "i16":
        return new int[] {0xffebeae6, 0xff1c1c1e, 0xffd96b38};
      case "iXS":
        return new int[] {0xfff7e6c4, 0xff2c1d10, 0xffd4882a};
      case "iRS":
        return new int[] {0xfff5e6e2, 0xff2c1e1d, 0xffc97b6d};
      case "iRC":
        return new int[] {0xff18191b, 0xfff2f0ea, 0xff4a78a8};
      case "iAM":
        return new int[] {0xfff3dfb9, 0xff2e1f12, 0xffd67a22};
      case "A":
      case "EB":
        return new int[] {0xff2b2b2b, 0xfff4f1e8, 0xff9a9a9a};
      default:
        return r.raw
            ? new int[] {0xff1f6f4a, 0xfff2f2ea, 0xffcfd8cf}
            : new int[] {0xffe2622f, 0xfffff8ef, 0xfff4f1e8};
    }
  }

  private void layoutFrame() {
    if (back == null) return;
    back.setPhotoRatio(Framing.portraitRatio(ratio));
    back.requestLayout();
    back.post(back::reportPreview);
  }

  private GrainCamera.Snapshot snapshot() {
    return new GrainCamera.Snapshot(
        recipe,
        strengths[recipe],
        zoom,
        ratio,
        keepFull,
        ev,
        tuning.effective(Recipe.ALL[recipe]),
        shutterNs,
        isoSetting,
        flashMode);
  }

  private void selectRecipe(int id) {
    if (busy) return;
    recipe = id;
    savePrefs();
    updateLabels();
    camera.update(snapshot());
    Recipe r = Recipe.ALL[id];
      String sub =
        r.raw
            ? (r.fuji.apple()
                ? ("iphone-xs-max-warm".equals(r.fuji.key)
                    ? "A12 Smart HDR · Warm + Ultra HDR"
                    : "Smart HDR 5 · Vibrant + Ultra HDR")
                : r.standard
                    ? "film simulation · developed from RAW"
                    : "recipe · developed from RAW")
            : "phone ISP look";
    if (r.raw) {
      if (ready && !camera.rawAvailable())
        message("RAW isn’t available here: " + camera.rawUnavailableReason());
      else if (!r.fuji.apple() && !luts.has(r.fuji.filmSimulation))
        message(
            r.fuji.sony()
                ? "Import Sony’s free s709 LUT to use " + r.name + " (MENU → I.Q. → Import LUT zip)"
                : "Import Fujifilm’s LUT pack to use " + r.name + " (MENU → I.Q. → Import LUT zip)");
      else if (!r.fuji.exposureNote.isEmpty()) sub = "usually shot at " + r.fuji.exposureNote;
    }
    if (back != null) back.toast(r.name.toUpperCase(Locale.ROOT), sub);
  }

  private void updateLabels() {
    if (back == null) return;
    Recipe current = Recipe.ALL[recipe];
    int at = indexOfFilm(recipe);
    if (at >= 0) back.film.setIndex(at);
    int[] colors = memoColors(current);
    back.memo.set(current.name, filmCode(current), current.raw ? "RAW" : "ISP", colors[0], colors[1], colors[2]);
    back.exposure.setIndex(evCustom ? EV_C : evIndex(ev));
    back.shutterDial.setIndex(isoDial ? indexOf(isoValues, isoSetting) : indexOf(shutterValues, shutterNs));
    back.setIsoMode(isoDial);
    back.setFlashMode(flashMode);
    back.setPhotoRatio(Framing.portraitRatio(ratio));
    pushOsd();
    if ("q".equals(mode)) refreshQ();
    if ("menu".equals(mode)) refreshMenu();
  }

  private void setZoom(double value) {
    if (busy) return;
    double z = Framing.zoom(value);
    if (Math.abs(z - zoom) < 1e-4) return;
    zoom = z;
    camera.update(snapshot());
    pushOsd();
  }

  private void showFilmLab() {
    if (busy) return;
    dismissCameraTool();
    Dialog dialog = new Dialog(this);
    LinearLayout body = column();
    body.setPadding(0, dp(8), 0, dp(20));
    View handle = new View(this);
    handle.setBackground(bg(0xffc9cebb, 4, 0, 0));
    LinearLayout.LayoutParams hp = size(30, 3);
    hp.gravity = Gravity.CENTER;
    hp.bottomMargin = dp(10);
    body.addView(handle, hp);
    sheetHeader(
        body, "A feeling, fine-tuned.", "CHANGES ARE LIVE. NO APPLY BUTTON.", dialog::dismiss);
    HorizontalScrollView carousel = new HorizontalScrollView(this);
    carousel.setHorizontalScrollBarEnabled(false);
    LinearLayout cards = row();
    cards.setPadding(dp(22), dp(8), dp(22), dp(13));
    carousel.addView(cards);
    body.addView(carousel, new LinearLayout.LayoutParams(-1, -2));
    TextView description = paragraph(Recipe.ALL[recipe].description);
    description.setPadding(dp(23), 0, dp(23), dp(6));
    body.addView(description);
    LinearLayout controls = column();
    controls.setPadding(dp(22), 0, dp(22), 0);
    body.addView(controls);
    View separator = new View(this);
    separator.setBackgroundColor(LINE);
    LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(-1, dp(1));
    sep.setMargins(0, dp(8), 0, dp(13));
    controls.addView(separator, sep);
    LinearLayout strengthRow = row();
    TextView strengthName = text("Film strength", 11, INK);
    strengthName.setGravity(Gravity.START);
    strengthRow.addView(strengthName, new LinearLayout.LayoutParams(0, dp(28), 1));
    TextView amount = text(strengths[recipe] + "%", 10, INK);
    strengthRow.addView(amount);
    controls.addView(strengthRow);
    SeekBar strength = styledSlider(100, strengths[recipe]);
    controls.addView(strength, new LinearLayout.LayoutParams(-1, dp(32)));
    LinearLayout recipePanel = column();
    controls.addView(recipePanel, new LinearLayout.LayoutParams(-1, -2));
    boolean rawNow = Recipe.ALL[recipe].raw;
    strengthRow.setVisibility(rawNow ? View.GONE : View.VISIBLE);
    strength.setVisibility(rawNow ? View.GONE : View.VISIBLE);
    fillRecipePanel(recipePanel, Recipe.ALL[recipe], dialog);
    ArrayList<LinearLayout> tiles = new ArrayList<>();
    Bitmap artwork = null;
    try (java.io.InputStream in = getAssets().open("film-reference.jpg")) {
      BitmapFactory.Options artworkOptions = new BitmapFactory.Options();
      artworkOptions.inSampleSize = 4;
      artwork = BitmapFactory.decodeStream(in, null, artworkOptions);
    } catch (Exception ignored) {
    }
    for (Recipe f : Recipe.DISPLAY_ORDER) {
      if (!visible(f)) continue;
      LinearLayout tile = column();
      tile.setTag(f.id);
      tile.setPadding(dp(5), dp(5), dp(5), dp(5));
      LinearLayout.LayoutParams tp = size(97, 157);
      tp.setMargins(0, 0, dp(11), 0);
      cards.addView(tile, tp);
      tiles.add(tile);
      ImageView art = new ImageView(this);
      art.setScaleType(ImageView.ScaleType.CENTER_CROP);
      boolean lutPreview =
          f.raw && (f.fuji.apple() || luts.has(f.fuji.filmSimulation)) && artwork != null;
      String previewKey =
          lutPreview
              ? ((f.fuji.apple() || f.fuji.sensorCalibrated()) ? f.fuji.key : f.fuji.filmSimulation)
              : null;
      Bitmap cachedPreview = previewKey != null ? lutPreviews.get(previewKey) : null;
      art.setImageBitmap(cachedPreview != null ? cachedPreview : artwork);
      if (lutPreview) {
        if (cachedPreview == null) previewThroughLut(art, previewKey, f.fuji, artwork);
        art.setContentDescription(
            f.fuji.apple()
                ? "Reference picture through Apple " + f.fuji.filmLabel + "; approximate"
                : "Reference picture through "
                    + (f.fuji.sony() ? "Sony’s " : "Fujifilm’s ")
                    + f.fuji.filmLabel
                    + " LUT; approximate");
      } else {
        float[][] artGrades = {
          {1.03f, 1, .93f}, {.92f, 1.02f, 1.06f}, {1.1f, 1.01f, .84f}, {.74f, .8f, .88f},
          {1.05f, .98f, .86f}
        };
        float[] grade = artGrades[Math.min(f.id, artGrades.length - 1)];
        art.setColorFilter(
            new ColorMatrixColorFilter(
                new float[] {
                  grade[0], 0, 0, 0, 0, 0, grade[1], 0, 0, 0, 0, 0, grade[2], 0, 0, 0, 0, 0, 1, 0
                }));
        art.setContentDescription("Film collection reference artwork, not a live recipe preview");
      }
      art.setBackground(bg(0, 4, 0, 0));
      art.setClipToOutline(true);
      FrameLayout picture = new FrameLayout(this);
      picture.addView(art, new FrameLayout.LayoutParams(-1, -1));
      TextView check = text("", 10, INK);
      GrainStyle.icon(check, "check", INK, 11);
      check.setPadding(dp(4), 0, 0, 0);
      check.setBackground(bg(PAPER, 30, 0, 0));
      check.setTag("selected-check");
      FrameLayout.LayoutParams checkLp =
          new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.RIGHT | Gravity.TOP);
      checkLp.setMargins(0, dp(5), dp(5), 0);
      picture.addView(check, checkLp);
      tile.addView(picture, new LinearLayout.LayoutParams(-1, dp(101)));
      TextView title = text(f.name, f.name.length() > 10 ? 11 : 14, INK);
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      title.setTypeface(Typeface.SERIF);
      title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      title.setPadding(dp(3), 0, 0, 0);
      tile.addView(title, new LinearLayout.LayoutParams(-1, dp(29)));
      TextView iso =
          text(
              f.raw && f.fuji.apple()
                  ? ("iphone-xs-max-warm".equals(f.fuji.key) ? "A12 SMART HDR" : "SMART HDR 5 · HDR")
                  : f.standard
                      ? f.fuji.sony() ? "SONY LOOK" : "FILM SIMULATION"
                      : f.raw ? "RAW · RECIPE" : "ISP LOOK",
              8,
              f.raw ? ORANGE : MUTED);
      iso.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
      iso.setLetterSpacing(.04f);
      iso.setGravity(Gravity.START);
      iso.setPadding(dp(3), 0, 0, 0);
      tile.addView(iso);
      tile.setClickable(true);
      tile.setFocusable(true);
      tile.setContentDescription("Select " + f.name);
      tile.setOnClickListener(
          v -> {
            selectRecipe(f.id);
            description.setText(f.description);
            strength.setProgress(strengths[recipe]);
            amount.setText(strengths[recipe] + "%");
            strengthRow.setVisibility(f.raw ? View.GONE : View.VISIBLE);
            strength.setVisibility(f.raw ? View.GONE : View.VISIBLE);
            fillRecipePanel(recipePanel, f, dialog);
            for (LinearLayout t : tiles) styleFilmTile(t, Integer.valueOf(recipe).equals(t.getTag()));
          });
      styleFilmTile(tile, f.id == recipe);
    }
    strength.setOnSeekBarChangeListener(
        seek(
            p -> {
              strengths[recipe] = p;
              amount.setText(p + "%");
              savePrefs();
              updateLabels();
              camera.update(snapshot());
            }));
    TextView artNote =
        text(
            "Film tiles: a reference picture through each official LUT (approximate). Your"
                + " photos are developed from RAW.",
            8,
            MUTED);
    artNote.setGravity(Gravity.START);
    LinearLayout.LayoutParams an = new LinearLayout.LayoutParams(-1, -2);
    an.setMargins(dp(22), dp(8), dp(22), 0);
    body.addView(artNote, an);
    LinearLayout footer = row();
    footer.setPadding(dp(22), dp(14), dp(22), 0);
    body.addView(footer);
    TextView reset =
        button(
            "Reset this look",
            10,
            MUTED,
            () -> {
              Recipe current = Recipe.ALL[recipe];
              if (current.raw) {
                boolean wasTuned = tuning.tuned(current);
                tuning.reset(current);
                fillRecipePanel(recipePanel, current, dialog);
                updateLabels();
                message(
                    (wasTuned ? "Back to " : "Already at ")
                        + (current.fuji.sony()
                            ? "plain s709"
                            : current.fuji.apple()
                                ? "default Photographic Style"
                                : current.standard
                                    ? "Fujifilm’s standard settings"
                                    : "the published recipe"));
                return;
              }
              strengths[recipe] = 80;
              strength.setProgress(80);
              amount.setText("80%");
              savePrefs();
              updateLabels();
              camera.update(snapshot());
            });
    reset.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    footer.addView(reset, new LinearLayout.LayoutParams(0, dp(39), 1));
    TextView done = button("Back to shooting ↗", 10, PAPER, dialog::dismiss);
    done.setBackground(bg(INK, 25, 0, 0));
    footer.addView(done, size(143, 39));
    int maxSheet = Math.round(getResources().getDisplayMetrics().heightPixels * 0.9f);
    // The tuning list is taller than many screens: the sheet scrolls, up to 90 % of the height.
    ScrollView sheet =
        new ScrollView(this) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int limit = maxSheet;
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
              limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
            super.onMeasure(
                widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
          }
        };
    sheet.setVerticalScrollBarEnabled(false);
    sheet.setOverScrollMode(View.OVER_SCROLL_NEVER);
    sheet.setBackground(bg(PAPER, 25, 0, 0));
    sheet.setClipToOutline(true);
    sheet.addView(body, new FrameLayout.LayoutParams(-1, -2));
    dialog.setContentView(sheet);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setDimAmount(.08f);
      window.setGravity(Gravity.BOTTOM);
      window.setLayout(-1, -2);
    }
  }

  private void previewThroughLut(ImageView art, String cacheKey, FujiRecipe fuji, Bitmap artwork) {
    files.execute(
        () -> {
          try {
            int w = artwork.getWidth(), h = artwork.getHeight();
            int[] px = new int[w * h];
            artwork.getPixels(px, 0, w, 0, 0, w, h);
            CubeLut lut = fuji.apple() ? null : luts.load(fuji.filmSimulation);
            LutPreview.apply(px, lut, (fuji.apple() || fuji.sensorCalibrated()) ? fuji : null);
            Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            runOnUiThread(
                () -> {
                  lutPreviews.put(cacheKey, out);
                  art.setColorFilter(null);
                  art.setImageBitmap(out);
                });
          } catch (Exception e) {
            android.util.Log.w("Grain", "LUT preview failed for " + cacheKey, e);
          }
        });
  }

  /** Film Lab body for RAW recipes: published settings, LUT status and import. */
  private void fillRecipePanel(LinearLayout panel, Recipe r, Dialog dialog) {
    panel.removeAllViews();
    if (!r.raw) return;
    boolean apple = r.fuji.apple();
    boolean hasLut = apple || luts.has(r.fuji.filmSimulation);
    boolean hasRaw = !ready || camera.rawAvailable();
    boolean sony = r.fuji.sony();
    TextView state =
        text(
            !hasRaw
                ? "RAW unavailable: " + camera.rawUnavailableReason()
                : apple
                    ? "Built-in Apple Smart HDR RAW pipeline · Crisp Detail + Vibrant sRGB + Ultra HDR"
                    : hasLut
                        ? (sony ? "Sony " : "Fujifilm ") + r.fuji.filmLabel + " LUT ready · " + luts.summary()
                        : sony
                            ? "Needs Sony’s free official s709 LUT (s709Cubes.zip, about 0.5 MB)"
                            : "Needs Fujifilm’s free official LUT pack (" + r.fuji.filmLabel + " inside)",
            10,
            hasLut && hasRaw ? INK : ORANGE);
    state.setGravity(Gravity.START);
    panel.addView(state, new LinearLayout.LayoutParams(-1, -2));
    if (!apple) {
      LinearLayout actions = row();
      actions.setPadding(0, dp(8), 0, dp(8));
      TextView importButton =
          button(
              hasLut ? "Re-import" : sony ? "Import s709 zip" : "Import LUT pack",
              10,
              PAPER,
              () -> pickLuts(() -> fillRecipePanel(panel, r, dialog)));
      importButton.setBackground(bg(INK, 25, 0, 0));
      actions.addView(importButton, new LinearLayout.LayoutParams(0, dp(36), 1));
      View gap = new View(this);
      actions.addView(gap, size(8, 1));
      TextView get =
          sony
              ? button("Download from Sony ↗", 10, INK, this::openSonyDownload)
              : button("Download from Fujifilm ↗", 10, INK, this::openLutDownload);
      get.setBackground(bg(PAPER, 25, LINE, 1));
      actions.addView(get, new LinearLayout.LayoutParams(0, dp(36), 1));
      panel.addView(actions);
    }
    FujiRecipe.Tuning work = tuning.effective(r).tuning();
    FujiRecipe.Tuning defaults = r.fuji.tuning();
    LinearLayout head = row();
    head.setPadding(0, dp(4), 0, dp(2));
    TextView heading = text("SETTINGS", 9, MUTED);
    heading.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
    heading.setLetterSpacing(.05f);
    heading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    head.addView(heading, new LinearLayout.LayoutParams(0, dp(24), 1));
    TextView badge = text("", 9, MUTED);
    badge.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
    head.addView(badge, new LinearLayout.LayoutParams(-2, dp(24)));
    panel.addView(head);
    Runnable showBadge =
        () -> {
          boolean isTuned = tuning.tuned(r);
          badge.setText(
              isTuned
                  ? "TUNED · ORANGE = CHANGED"
                  : sony
                      ? "DEFAULT · SONY S709"
                      : r.standard ? "DEFAULT · FUJIFILM STANDARD" : "DEFAULT · PUBLISHED RECIPE");
          badge.setTextColor(isTuned ? ORANGE : MUTED);
        };
    showBadge.run();
    // every row refreshes after a change: one setting can switch another off (DR-P locks Highlight
    // and Shadow; Color temperature applies only to Kelvin)
    ArrayList<Runnable> rows = new ArrayList<>();
    Runnable changed =
        () -> {
          tuning.save(r, work);
          showBadge.run();
          updateLabels();
          for (Runnable shown : rows) shown.run();
          // the viewfinder follows a fixed white balance with the nearest Camera2 preset
          if (r.id == recipe && camera != null) camera.update(snapshot());
        };
    if (sony) fixedSetting(panel, "Look", "Sony s709");
    else fixedSetting(panel, "Film simulation", r.fuji.filmLabel);
    if (r.fuji.apple()) {
      // iPhone looks: gallery-editor sliders replace the Fujifilm steppers. White balance and
      // Kelvin stay as steppers because the viewfinder follows them; everything else is the editor.
      for (TuningControl setting : TuningControl.fujifilm(r.fuji)) {
        if (!setting.name.equals("White balance") && !setting.name.equals("Color temperature"))
          continue;
        rows.add(stepper(panel, setting, work, defaults, changed));
      }
      rows.addAll(buildAppleEditor(panel, r, work, defaults, changed));
    } else {
      for (TuningControl setting : TuningControl.fujifilm(r.fuji))
        rows.add(stepper(panel, setting, work, defaults, changed));
    }
    if (sony) {
      TextView sonyNote =
          paragraph(
              "s709 is a cinema look with no stills settings of its own. The settings above are"
                  + " Grain’s, in Fujifilm’s menu units, applied around Sony’s LUT; all at 0 / Off is"
                  + " plain s709. Fujifilm-only settings (Dynamic range, Color Chrome Effect, FX"
                  + " Blue) aren’t offered.");
      sonyNote.setPadding(0, dp(6), 0, 0);
      panel.addView(sonyNote);
    }
    TextView extrasTitle = text(sony ? "GRAIN EXTRAS" : "GRAIN EXTRAS · NOT FUJIFILM SETTINGS", 9, MUTED);
    extrasTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
    extrasTitle.setLetterSpacing(.04f);
    extrasTitle.setGravity(Gravity.START);
    extrasTitle.setPadding(0, dp(14), 0, dp(4));
    panel.addView(extrasTitle, new LinearLayout.LayoutParams(-1, -2));
    for (TuningControl setting : TuningControl.extras()) {
      // iPhone looks carry Development inside the gallery editor (GRAIN group) — don't render it twice
      if (r.fuji.apple() && setting.name.equals("Development")) continue;
      rows.add(stepper(panel, setting, work, defaults, changed));
    }
    TextView extrasNote =
        paragraph(
            (apple
                    ? "Development lives in the editor above (GRAIN): Clean is the published iPhone"
                        + " look with its Smart-HDR smoothing; First version develops like Grain v0.2.0 —"
                        + " the smoothing stages stay off, so hair, beard and fabric texture come through"
                        + " grittier, like the Fuji films. The white balance, tone and colour of the"
                        + " iPhone look stay exactly the same.\n"
                    : "Development: First version develops like Grain v0.2.0 — colour noise reduction"
                        + " only, no sharpening at Sharpness −2 and the full grain, so the picture is"
                        + " grittier and more like film. Raising High ISO NR adds luminance noise"
                        + " reduction and Sharpness above −2 sharpens. Clean (v0.2.1 and later) removes"
                        + " more noise and sharpens. Reggie’s Portra and Reggie’s Superia start on First"
                        + " version, every other film on Clean.\n")
                + "No Fujifilm film simulation has halation or bloom, so both start Off. Halation: a"
                + " red-orange glow around bright lights, like film without an anti-halation"
                + " layer. Bloom: a soft glow spreading from highlights, like a diffusion filter."
                + " Both are made from the RAW light before the film simulation.");
    extrasNote.setPadding(0, dp(6), 0, 0);
    panel.addView(extrasNote);
    String creditText =
        sony
            ? "Default: Sony s709 at standard settings (every setting 0 / Off, Auto white"
                + " balance). LUT: Sony’s official s709, S-Log3 / S-Gamut3.Cine to Rec.709 — "
                + r.fuji.source
            : apple
                ? "Default: iPhone 16 Pro "
                    + r.fuji.filmLabel
                    + " Photographic Style, developed directly in wide-gamut Display P3 with ISO"
                    + " 21496-1 Ultra HDR highlight headroom — "
                    + r.fuji.source
                : r.standard
                    ? "Default: Fujifilm " + r.fuji.filmLabel + " at standard settings (every setting"
                        + " 0 / Off, Auto white balance). LUT: Fujifilm’s official F-Log2 pack."
                    : "Default: the recipe by " + r.fuji.author + " as published — " + r.fuji.source;
    if (!r.fuji.exposureNote.isEmpty())
      creditText +=
          "\nTypical exposure compensation: " + r.fuji.exposureNote
              + ". A guide only: Grain doesn’t apply it, set it with the exposure control.";
    if (r.fuji.monochrome() && (r.fuji.colorChrome > 0 || r.fuji.colorChromeBlue > 0))
      creditText +=
          "\nThe published Color Chrome settings don’t change a black-and-white picture in Grain,"
              + " so they aren’t shown.";
    TextView credit = paragraph(creditText);
    credit.setPadding(0, dp(2), 0, 0);
    panel.addView(credit);
    TextView note =
        paragraph(
            sony
                ? "Tuning changes the developed photo; the viewfinder is the phone’s preview. Each"
                    + " photo is developed from the sensor RAW into Sony’s S-Log3 / S-Gamut3.Cine"
                    + " (Sony’s published formulas, converted exactly for every pixel), then through"
                    + " Sony’s s709 LUT. Grain’s choices: the average is metered to Sony’s 18 % grey,"
                    + " and when the phone’s RAW clips below s709’s white the highlight shoulder is"
                    + " stretched so clipped areas still reach white. The settings and extras are"
                    + " Grain’s approximations. s709 is lower in contrast and darker than"
                    + " Fujifilm’s stills looks, as Sony designed it."
                : apple
                    ? "Developed directly from the sensor RAW with no external LUT required: 2/3 EV"
                        + " highlight headroom, texture-preserving Smart HDR local tone mapping"
                        + " (redescending detail isolation + Deep-Fusion micro-contrast, zero"
                        + " silhouette halos), hue-preserving RefBaselineRGBTone global S-curve,"
                        + " OKLab background vibrance with protected skin tones in Universal"
                        + " Vibrant sRGB, plus an Android 14+ Ultra HDR Gainmap for OLED"
                        + " highlights."
                    : "Tuning changes the developed photo; the viewfinder is the phone’s preview, with the"
                        + " nearest white-balance preset when the film uses a fixed white balance. Each"
                        + " photo is developed from the sensor RAW with Fujifilm’s LUT plus Grain’s stills"
                        + " tone model. Fixed white balances use Fujifilm’s preset white points measured"
                        + " from Fujifilm RAW files. Dynamic range and D-Range Priority, WB shift,"
                        + " Clarity, Grain, Color Chrome, FX Blue, the Acros filters and the extras are"
                        + " Grain’s approximations.");
    panel.addView(note);
  }

  /** Gallery: the LUT or built-in colour pipeline a photo was developed with. */
  private static String lutLabel(String lut) {
    dev.grain.photo.develop.AppleRender.Style st =
        dev.grain.photo.develop.AppleRender.Style.fromSimulation(lut);
    if (st != null) return st.label + " · P3 Ultra HDR";
    return FujiRecipe.sonyLut(lut) ? "Sony s709 LUT" : lut + " LUT";
  }

  private void fixedSetting(LinearLayout panel, String name, String value) {
    LinearLayout line = row();
    TextView key = text(name, 10, INK);
    key.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    line.addView(key, new LinearLayout.LayoutParams(0, dp(34), 1));
    TextView shown = text(value, 10, INK);
    line.addView(shown, size(160, 28));
    panel.addView(line);
  }

  /**
   * The iPhone editor: gallery-style sliders (−100..+100, neutral 0, orange when changed) grouped
   * LIGHT / COLOR / DETAIL, plus the tone-curve editor. Returns the rows' refresh runnables.
   */
  private ArrayList<Runnable> buildAppleEditor(
      LinearLayout panel,
      Recipe r,
      FujiRecipe.Tuning work,
      FujiRecipe.Tuning defaults,
      Runnable fujiChanged) {
    final ArrayList<Runnable> edits = new ArrayList<>();
    final AppleTune aw = tuning.appleEffective(r.fuji);
    final Runnable tuneChanged =
        () -> {
          tuning.appleSave(r.fuji, aw);
          fujiChanged.run();
        };
    editorGroup(panel, "LIGHT");
    edits.add(appleSlider(panel, "Exposure", aw, t -> t.exposure, (t, i) -> { t.exposure = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Brightness", aw, t -> t.brightness, (t, i) -> { t.brightness = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Lightness", aw, t -> t.lightness, (t, i) -> { t.lightness = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Contrast", aw, t -> t.contrast, (t, i) -> { t.contrast = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Highlights", aw, t -> t.highlights, (t, i) -> { t.highlights = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Shadows", aw, t -> t.shadows, (t, i) -> { t.shadows = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Light range", aw, t -> t.lightRange, (t, i) -> { t.lightRange = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Dark range", aw, t -> t.darkRange, (t, i) -> { t.darkRange = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Whites", aw, t -> t.whites, (t, i) -> { t.whites = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Blacks", aw, t -> t.blacks, (t, i) -> { t.blacks = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Fade", aw, t -> t.fade, (t, i) -> { t.fade = i; }, tuneChanged));
    edits.add(appleCurveRow(panel, aw, tuneChanged));
    editorGroup(panel, "COLOR");
    edits.add(appleSlider(panel, "Saturation", aw, t -> t.saturation, (t, i) -> { t.saturation = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Vibrance", aw, t -> t.vibrance, (t, i) -> { t.vibrance = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Warmth", aw, t -> t.warmth, (t, i) -> { t.warmth = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Tint", aw, t -> t.tint, (t, i) -> { t.tint = i; }, tuneChanged));
    editorGroup(panel, "DETAIL");
    edits.add(appleSlider(panel, "Sharpness", aw, t -> t.sharpness, (t, i) -> { t.sharpness = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Noise reduction", aw, t -> t.noiseReduction, (t, i) -> { t.noiseReduction = i; }, tuneChanged));
    edits.add(appleSlider(panel, "Clarity", aw, t -> t.clarity, (t, i) -> { t.clarity = i; }, tuneChanged));
    editorGroup(panel, "GRAIN");
    edits.add(appleDevelopmentRow(panel, work, defaults, tuneChanged));
    return edits;
  }

  /**
   * iPhone tuner row: how the RAW is developed — Clean (the published iPhone look, with its
   * Smart-HDR smoothing stages) or First version (Grain v0.2.0's render: grittier, more texture,
   * like the Fuji recipes default to). Same choice the Fuji films carry under GRAIN EXTRAS.
   */
  private Runnable appleDevelopmentRow(
      LinearLayout panel,
      final FujiRecipe.Tuning work,
      final FujiRecipe.Tuning defaults,
      final Runnable onChanged) {
    final FujiRecipe.Development[] ways = FujiRecipe.Development.values();
    LinearLayout line = row();
    TextView key = text("Development", 10, INK);
    key.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    key.setContentDescription(
        "Development. Clean is the iPhone look; First version is grittier with more texture. Long-press resets to the recipe default.");
    final TextView[] chips = new TextView[ways.length];
    LinearLayout seg = row();
    final Runnable show =
        () -> {
          boolean diverged = work.development != defaults.development;
          key.setTextColor(diverged ? ORANGE : INK);
          for (int i = 0; i < ways.length; i++) {
            boolean sel = work.development == ways[i];
            chips[i].setTextColor(sel ? PAPER : INK);
            chips[i].setBackground(sel ? bg(INK, 15, INK, 1) : bg(PAPER, 15, LINE, 1));
          }
        };
    for (int i = 0; i < ways.length; i++) {
      final int v = i;
      chips[i] = button(ways[i].label, 10, INK, () -> { work.development = ways[v]; onChanged.run(); });
      chips[i].setGravity(Gravity.CENTER);
      chips[i].setContentDescription("Develop as " + ways[i].label);
      seg.addView(chips[i], new LinearLayout.LayoutParams(0, dp(30), 1));
    }
    key.setOnLongClickListener(
        v -> {
          work.development = defaults.development;
          onChanged.run();
          return true;
        });
    line.addView(key, size(96, 32));
    line.addView(seg, new LinearLayout.LayoutParams(0, dp(32), 1));
    panel.addView(line);
    show.run();
    return show;
  }

  private void editorGroup(LinearLayout panel, String label) {
    TextView heading = text(label, 9, MUTED);
    heading.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
    heading.setLetterSpacing(.05f);
    heading.setPadding(0, dp(10), 0, dp(2));
    panel.addView(heading);
  }

  /** One editor slider row (long-press the name resets to 0). Returns the row's refresh. */
  private Runnable appleSlider(
      LinearLayout panel,
      String name,
      final AppleTune aw,
      ToIntFunction<AppleTune> get,
      ObjIntConsumer<AppleTune> set,
      final Runnable onChanged) {
    LinearLayout line = row();
    TextView key = text(name, 10, INK);
    key.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    key.setContentDescription(name + ". Long-press resets to zero.");
    final SeekBar bar = new SeekBar(this);
    bar.setMax(AppleTune.MAX - AppleTune.MIN);
    final TextView value = text("", 10, INK);
    value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    final boolean[] syncing = {false};
    final Runnable show =
        () -> {
          int v = get.applyAsInt(aw);
          syncing[0] = true;
          bar.setProgress(v - AppleTune.MIN);
          syncing[0] = false;
          value.setText(v == 0 ? "0" : v > 0 ? "+" + v : String.valueOf(v));
          value.setContentDescription(name + " " + v + (v == 0 ? ", neutral" : ", changed"));
          value.setTextColor(v == 0 ? INK : ORANGE);
          key.setTextColor(v == 0 ? INK : ORANGE);
        };
    key.setOnLongClickListener(
        v -> {
          if (get.applyAsInt(aw) == 0) return true;
          set.accept(aw, 0);
          onChanged.run();
          return true;
        });
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser || syncing[0]) return;
            set.accept(aw, progress + AppleTune.MIN);
            int v = get.applyAsInt(aw);
            value.setText(v == 0 ? "0" : v > 0 ? "+" + v : String.valueOf(v));
            value.setTextColor(v == 0 ? INK : ORANGE);
            key.setTextColor(v == 0 ? INK : ORANGE);
          }

          @Override
          public void onStartTrackingTouch(SeekBar seekBar) {}

          @Override
          public void onStopTrackingTouch(SeekBar seekBar) {
            onChanged.run();
          }
        });
    line.addView(key, size(96, 32));
    line.addView(bar, new LinearLayout.LayoutParams(0, dp(32), 1));
    line.addView(value, size(40, 32));
    panel.addView(line);
    show.run();
    return show;
  }

  /** The tone-curve row: state label + Edit button (long-press the name restores the identity). */
  private Runnable appleCurveRow(LinearLayout panel, final AppleTune aw, final Runnable onChanged) {
    LinearLayout line = row();
    TextView key = text("Tone curve", 10, INK);
    key.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    key.setContentDescription("Tone curve. Long-press resets to default.");
    final TextView state = text("", 10, INK);
    state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    TextView edit = button("Edit", 10, INK, () -> openAppleCurveEditor(aw, onChanged));
    edit.setBackground(bg(PAPER, 15, LINE, 1));
    edit.setContentDescription("Edit tone curve");
    final Runnable show =
        () -> {
          int n = aw.curveX.length;
          state.setText(n == 0 ? "Default" : n + " knots");
          state.setTextColor(n == 0 ? MUTED : ORANGE);
          key.setTextColor(n == 0 ? INK : ORANGE);
        };
    key.setOnLongClickListener(
        v -> {
          if (aw.curveX.length == 0) return true;
          aw.curveX = new float[0];
          aw.curveY = new float[0];
          onChanged.run();
          return true;
        });
    line.addView(key, size(96, 32));
    line.addView(state, new LinearLayout.LayoutParams(0, dp(32), 1));
    line.addView(edit, size(90, 30));
    panel.addView(line);
    show.run();
    return show;
  }

  private void openAppleCurveEditor(final AppleTune aw, final Runnable onChanged) {
    final AppleTuneCurveView view = new AppleTuneCurveView(this, aw);
    int pad = dp(12);
    LinearLayout box = new LinearLayout(this);
    box.setPadding(pad, 0, pad, 0);
    box.addView(view, new LinearLayout.LayoutParams(-1, -2));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Tone curve")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset", null)
            .setPositiveButton(
                "Done",
                (dlg, w) -> {
                  view.commit();
                  onChanged.run();
                })
            .show();
    // keep Reset from dismissing the dialog
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> view.reset());
  }

  /**
   * Free tone-curve editor: square graph, drag knots, tap empty space to add a knot, drag an
   * interior knot below the graph to remove it. The curve stays monotone by construction; values
   * commit to the tune only on Done.
   */
  private final class AppleTuneCurveView extends View {
    private final AppleTune tune;
    private float[] xs, ys;
    private int drag = -1;
    private final Paint frame, grid, diag, curve, dots, dotsHot, paperBg;

    AppleTuneCurveView(android.content.Context context, AppleTune aw) {
      super(context);
      tune = aw;
      if (aw.curveX.length >= 2) {
        xs = aw.curveX.clone();
        ys = aw.curveY.clone();
      } else {
        xs = new float[] {0f, 1f};
        ys = new float[] {0f, 1f};
      }
      frame = stroke(LINE, 2);
      grid = stroke(LINE, 1);
      diag = stroke(LINE, 1);
      curve = stroke(INK, 3);
      dots = fill(INK);
      dotsHot = fill(ORANGE);
      paperBg = fill(PAPER);
    }

    private Paint stroke(int color, float wDp) {
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setStyle(Paint.Style.STROKE);
      p.setColor(color);
      p.setStrokeWidth(dp(wDp));
      return p;
    }

    private Paint fill(int color) {
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setStyle(Paint.Style.FILL);
      p.setColor(color);
      return p;
    }

    private float padPx() {
      return dp(16);
    }

    private float graphSize() {
      return Math.min(getWidth(), getHeight()) - 2 * padPx();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      int w = getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);
      w = Math.min(w, dp(430));
      setMeasuredDimension(w, w);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float pad = padPx(), s = graphSize();
      canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(10), dp(10), paperBg);
      for (int i = 0; i <= 4; i++) {
        float g = pad + s * i / 4f;
        canvas.drawLine(pad, g, pad + s, g, grid);
        canvas.drawLine(g, pad, g, pad + s, grid);
      }
      canvas.drawLine(pad, pad + s, pad + s, pad, diag); // identity
      canvas.drawRect(pad, pad, pad + s, pad + s, frame);
      Path path = new Path();
      for (int i = 0; i <= 128; i++) {
        float x = i / 128f;
        float y = AppleTune.curveAt(xs, ys, x);
        float px = pad + x * s, py = pad + (1f - y) * s;
        if (i == 0) path.moveTo(px, py);
        else path.lineTo(px, py);
      }
      canvas.drawPath(path, curve);
      float r = dp(6);
      for (int i = 0; i < xs.length; i++) {
        float px = pad + xs[i] * s, py = pad + (1f - ys[i]) * s;
        canvas.drawCircle(px, py, r, dots);
        if (i == drag) canvas.drawCircle(px, py, r * 0.55f, dotsHot);
      }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
      float pad = padPx(), s = graphSize();
      float fx = (event.getX() - pad) / s, fy = 1f - (event.getY() - pad) / s;
      switch (event.getActionMasked()) {
        case android.view.MotionEvent.ACTION_DOWN:
          int best = -1;
          float bestD = 0.07f;
          for (int i = 0; i < xs.length; i++) {
            float d = (float) Math.hypot(xs[i] - fx, ys[i] - fy);
            if (d < bestD) {
              bestD = d;
              best = i;
            }
          }
          if (best >= 0) {
            drag = best;
          } else if (fx > 0.02f && fx < 0.98f) {
            insert(fx, fy);
          }
          invalidate();
          return true;
        case android.view.MotionEvent.ACTION_MOVE:
          if (drag < 0) return true;
          boolean interior = drag > 0 && drag < xs.length - 1;
          if (interior && fy < -0.06f) {
            // drag an interior knot below the graph to remove it
            float[] nx = new float[xs.length - 1], ny2 = new float[ys.length - 1];
            System.arraycopy(xs, 0, nx, 0, drag);
            System.arraycopy(xs, drag + 1, nx, drag, xs.length - drag - 1);
            System.arraycopy(ys, 0, ny2, 0, drag);
            System.arraycopy(ys, drag + 1, ny2, drag, ys.length - drag - 1);
            xs = nx;
            ys = ny2;
            drag = -1;
            invalidate();
            return true;
          }
          float yLo = interior ? Math.min(ys[drag - 1], ys[drag + 1]) : 0f;
          float yHi = interior ? Math.max(ys[drag - 1], ys[drag + 1]) : 1f;
          if (interior)
            xs[drag] = Math.max(xs[drag - 1] + 0.02f, Math.min(xs[drag + 1] - 0.02f, fx));
          ys[drag] = Math.max(yLo, Math.min(yHi, fy));
          invalidate();
          return true;
        case android.view.MotionEvent.ACTION_UP:
          drag = -1;
          invalidate();
          return true;
        default:
          return true;
      }
    }

    private void insert(float fx, float fy) {
      int at = 0;
      while (at < xs.length && xs[at] < fx) at++;
      if (at == 0 || at >= xs.length) return;
      float yLo = Math.min(ys[at - 1], ys[at]), yHi = Math.max(ys[at - 1], ys[at]);
      float y = Math.max(yLo, Math.min(yHi, fy));
      float x = Math.max(xs[at - 1] + 0.02f, Math.min(xs[at] - 0.02f, fx));
      float[] nx = new float[xs.length + 1], nya = new float[ys.length + 1];
      System.arraycopy(xs, 0, nx, 0, at);
      System.arraycopy(xs, at, nx, at + 1, xs.length - at);
      System.arraycopy(ys, 0, nya, 0, at);
      System.arraycopy(ys, at, nya, at + 1, ys.length - at);
      nx[at] = x;
      nya[at] = y;
      xs = nx;
      ys = nya;
      drag = at;
    }

    void reset() {
      xs = new float[] {0f, 1f};
      ys = new float[] {0f, 1f};
      drag = -1;
      invalidate();
    }

    /** Writes the knots back to the tune; an identity curve becomes the default (no knots). */
    void commit() {
      boolean identity = true;
      for (int i = 0; i < xs.length; i++)
        if (Math.abs(ys[i] - xs[i]) > 0.004f) {
          identity = false;
          break;
        }
      if (identity) {
        tune.curveX = new float[0];
        tune.curveY = new float[0];
      } else {
        tune.curveX = xs.clone();
        tune.curveY = ys.clone();
      }
    }
  }

  /**
   * A setting row: name, then − value + (orange when it differs from the film's default; muted with
   * the reason while another setting overrides it). Returns the row's refresh.
   */
  private Runnable stepper(
      LinearLayout panel,
      TuningControl setting,
      FujiRecipe.Tuning work,
      FujiRecipe.Tuning defaults,
      Runnable changed) {
    LinearLayout line = row();
    TextView key = text(setting.name, 10, INK);
    key.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    line.addView(key, new LinearLayout.LayoutParams(0, dp(34), 1));
    TextView minus = text("−", 15, INK);
    TextView value = text("", 10, INK);
    TextView plus = text("+", 15, INK);
    for (TextView step : new TextView[] {minus, plus}) {
      step.setBackground(bg(PAPER, 15, LINE, 1));
      step.setClickable(true);
      step.setFocusable(true);
    }
    minus.setContentDescription("Decrease " + setting.name);
    plus.setContentDescription("Increase " + setting.name);
    line.addView(minus, size(34, 28));
    line.addView(value, size(92, 28));
    line.addView(plus, size(34, 28));
    int standard = setting.at(defaults);
    int last = setting.labels.length - 1;
    Runnable show =
        () -> {
          int i = setting.at(work);
          boolean on = setting.enabled(work);
          key.setTextColor(on ? INK : MUTED);
          if (!on) {
            value.setText(setting.disabledLabel);
            value.setTextColor(MUTED);
            value.setContentDescription(setting.name + ": " + setting.disabledLabel);
          } else {
            value.setText(setting.labels[i]);
            value.setTextColor(i == standard ? INK : ORANGE);
            value.setContentDescription(
                setting.name + " " + setting.labels[i]
                    + (i == standard ? ", default" : ", changed from " + setting.labels[standard]));
          }
          minus.setEnabled(on && i > 0);
          minus.setAlpha(on && i > 0 ? 1f : .3f);
          plus.setEnabled(on && i < last);
          plus.setAlpha(on && i < last ? 1f : .3f);
        };
    minus.setOnClickListener(
        v -> {
          if (!setting.enabled(work)) return;
          int i = setting.at(work);
          if (i <= 0) return;
          setting.set(work, i - 1);
          changed.run();
          show.run();
        });
    plus.setOnClickListener(
        v -> {
          if (!setting.enabled(work)) return;
          int i = setting.at(work);
          if (i >= last) return;
          setting.set(work, i + 1);
          changed.run();
          show.run();
        });
    show.run();
    panel.addView(line);
    return show;
  }

  private void pickLuts(Runnable after) {
    afterLutImport = after;
    try {
      lutPicker.launch(new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"});
    } catch (Exception e) {
      message("No file picker available: " + e.getMessage());
    }
  }

  private void openLutDownload() {
    new AlertDialog.Builder(this)
        .setTitle("Fujifilm’s official LUTs")
        .setMessage(
            "Download Fujifilm’s free \"GFX ETERNA 55\" 3D LUT pack (about 46 MB; it includes"
                + " Classic Chrome). Then come back and tap Import LUT pack, and choose the"
                + " downloaded zip. Grain stores the LUTs only inside the app.")
        .setPositiveButton(
            "Download zip",
            (d, w) -> openUrl(LutLibrary.RECOMMENDED_PACK))
        .setNeutralButton("Fujifilm page", (d, w) -> openUrl(LutLibrary.DOWNLOAD_PAGE))
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void openSonyDownload() {
    new AlertDialog.Builder(this)
        .setTitle("Sony’s official s709 LUT")
        .setMessage(
            "Download Sony’s free s709 LUT (s709Cubes.zip, about 0.5 MB) from Sony’s S-Log grading"
                + " guide. Then come back, tap Import s709 zip and choose the downloaded zip. Grain"
                + " uses its S-Log3 / S-Gamut3.Cine version and stores it only inside the app.")
        .setPositiveButton("Download zip", (d, w) -> openUrl(LutLibrary.SONY_ZIP))
        .setNeutralButton("Sony page", (d, w) -> openUrl(LutLibrary.SONY_PAGE))
        .setNegativeButton("Cancel", null)
        .show();
  }

  /** Settings: where to get each official LUT. */
  private void chooseLutDownload() {
    new AlertDialog.Builder(this)
        .setTitle("Official LUTs")
        .setItems(
            new String[] {"Fujifilm film simulations (GFX ETERNA 55 pack)", "Sony s709 (cinema look)"},
            (d, which) -> {
              if (which == 0) openLutDownload();
              else openSonyDownload();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void openUrl(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (Exception e) {
      message("No browser available");
    }
  }

  private void importLuts(Uri uri) {
    message("Importing LUTs…");
    files.execute(
        () -> {
          try {
            LutLibrary.ImportResult result = luts.importFrom(getContentResolver(), uri, getCacheDir());
            boolean chrome = luts.has("CLASSIC-CHROME");
            boolean sonyAdded = result.imported.contains(FujiRecipe.SONY_S709_LUT);
            int films = 0, videos = 0;
            for (String sim : result.imported)
              if (FujiRecipe.isTechnical(sim)) videos++;
              else if (!FujiRecipe.sonyLut(sim)) films++;
            int filmCount = films, technical = videos;
            runOnUiThread(
                () -> {
                  lutPreviews.clear();
                  buildFilmRow();
                  updateLabels();
                  String fuji =
                      filmCount == 0
                          ? ""
                          : filmCount + " film simulation" + (filmCount == 1 ? "" : "s") + " added to your films"
                              + (technical > 0 ? " (+" + technical + " video LUT" + (technical == 1 ? "" : "s") + ", not used for photos)" : "")
                              + (chrome ? "" : " · this pack has no Classic Chrome; use GFX ETERNA 55");
                  message(
                      sonyAdded
                          ? (fuji.isEmpty() ? "" : fuji + " · ") + "Sony s709 added to your films"
                          : !fuji.isEmpty()
                              ? fuji
                              : technical + " video LUT" + (technical == 1 ? "" : "s")
                                  + " imported; they aren’t used for photos");
                  Runnable after = afterLutImport;
                  afterLutImport = null;
                  if (after != null) after.run();
                });
          } catch (Exception e) {
            runOnUiThread(() -> message("LUT import failed: " + e.getMessage()));
          }
        });
  }

  private void applyRawFilesPreference() {
    File dir = new File(getCacheDir(), "raw");
    if (keepRawFiles && !dir.exists() && !dir.mkdirs()) keepRawFiles = false;
    camera.setDngDirectory(keepRawFiles ? dir : null);
  }

  private void showSettings() {
    if (busy) {
      message("Finish the current capture first.");
      return;
    }
    cancelTimer();
    dismissCameraTool();
    openPanel("Little preferences.", "SET ONCE. STAY IN THE MOMENT.");
    page = "settings";
    LinearLayout body = column();
    body.setPadding(dp(23), dp(15), dp(23), dp(28));
    panelScroll.addView(body);
    sectionLabel(body, "MAKE IT FEEL RIGHT");
    preferenceToggle(
        body,
        "Composition grid",
        "A little help finding your frame. Set the style under SET UP (FRAMING GRID): thirds, square, or diagonal + square.",
        gridStyle != 0,
        v -> {
          gridStyle = v ? Math.max(1, gridStyle) : 0;
          savePrefs();
          pushOsd();
        });
    preferenceToggle(
        body,
        "Keep full-frame capture",
        "Uncropped and unzoomed. The ISP recipe is still baked in.",
        keepFull,
        v -> {
          keepFull = v;
          savePrefs();
        });
    preferenceToggle(
        body,
        "Larger capture",
        "Up to 24 MP of public JPEG output. Restart camera to apply.",
        larger,
        v -> {
          larger = v;
          savePrefs();
        });
    TextView restart =
        button(
            "Restart camera",
            11,
            ORANGE,
            () -> {
              if (busy) return;
              closePanel();
              ready = false;
              camera.close();
              main.postDelayed(this::openCamera, 350);
            });
    restart.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    body.addView(restart, new LinearLayout.LayoutParams(-1, dp(43)));
    sectionLabel(body, "FUJIFILM RECIPES (RAW)");
    preferenceToggle(
        body,
        "Face exposure",
        "Like Fujifilm’s face detection: a face sets the exposure, so a backlit face comes out bright"
            + " and the background may blow out. Start at 0 EV.",
        faceExposure,
        v -> {
          faceExposure = v;
          savePrefs();
          camera.setFaceDetection(v);
          camera.update(snapshot());
        });
    preferenceToggle(
        body,
        "Face landmarks (eye AF)",
        "FULL face detection instead of SIMPLE: the camera reports each face’s id and its eye and"
            + " mouth positions. Slower than SIMPLE. This is the focus probe and the switch eye AF will"
            + " use; the receipt reports whether the landmarks actually arrived.",
        faceLandmarks,
        v -> {
          faceLandmarks = v;
          savePrefs();
          camera.setFaceLandmarks(v);
          camera.update(snapshot());
        });
    preferenceInfo(
        body,
        "Film LUTs",
        "Official free LUTs (Fujifilm’s pack, Sony’s s709), imported from your phone. They stay"
            + " inside Grain.",
        luts.summary());
    TextView importLut = button("Import a LUT zip ↗", 11, INK, () -> pickLuts(this::showSettings));
    importLut.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    body.addView(importLut, new LinearLayout.LayoutParams(-1, dp(43)));
    TextView getLut = button("Where to download them ↗", 11, MUTED, this::chooseLutDownload);
    getLut.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    body.addView(getLut, new LinearLayout.LayoutParams(-1, dp(40)));
    preferenceToggle(
        body,
        "Keep RAW test files",
        "Saves the sensor DNG and the phone’s own JPEG with each Fujifilm-recipe photo.",
        keepRawFiles,
        v -> {
          keepRawFiles = v;
          savePrefs();
          applyRawFilesPreference();
        });
    sectionLabel(body, "APP UPDATES");
    preferenceInfo(
        body,
        "Installed version",
        "Checks GitHub Releases (astanabesta/Grain) and verifies SHA-256 before installing.",
        availableUpdate != null
            ? "v" + BuildConfig.VERSION_NAME + " → " + availableUpdate.tagName
            : "v" + BuildConfig.VERSION_NAME);
    preferenceToggle(
        body,
        "Notify when an update is ready",
        "Checks on launch and offers one-tap in-app update whenever a newer APK is published.",
        notifyUpdates,
        v -> {
          notifyUpdates = v;
          savePrefs();
        });
    TextView updateBtn =
        button(
            availableUpdate != null
                ? "Install Grain " + availableUpdate.tagName + " ↗"
                : "Check for updates now ↗",
            11,
            availableUpdate != null ? ORANGE : INK,
            () -> {
              if (availableUpdate != null) showUpdateDialog(availableUpdate);
              else checkForUpdates(true);
            });
    updateBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    body.addView(updateBtn, new LinearLayout.LayoutParams(-1, dp(43)));
    sectionLabel(body, "YOUR PHOTOS. YOUR DEVICE.");
    preferenceInfo(body, "Photo storage", "No accounts. Nothing uploaded.", "Private on-device");
    preferenceInfo(
        body, "Preferences", "Film, strength, grid, ratio, timer.", "Remembered locally");
    preferenceInfo(
        body,
        "Photo format",
        "Full-resolution stills. Fujifilm recipes develop the sensor RAW on the phone.",
        camera.rawAvailable() ? "JPEG · RAW + DNG" : "JPEG · Camera2");
    TextView diagnostics = button("Camera diagnostics ↗", 11, INK, this::showDiagnostics);
    diagnostics.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    body.addView(diagnostics, new LinearLayout.LayoutParams(-1, dp(45)));
    TextView reset =
        button(
            "Reset camera preferences",
            11,
            ORANGE,
            () -> {
              recipe = 0;
              Arrays.fill(strengths, 80);
              ratio = "3:4";
              zoom = 1;
              ev = 0;
              timerSeconds = 0;
              drive = DRIVE_SINGLE;
              shutterNs = 0;
              isoSetting = 0;
              evCustom = false;
              aeLock = false;
              camera.setAeLock(false);
              focusMode = FocusMath.FOCUS_AFC;
              mfOn = false;
              camera.setFocusMode(focusMode);
              magnifier = FocusMath.MAG_4X_HOLD;
              bktFramesIndex = 1;
              bktStepMode = FocusMath.BKT_STEP_MEDIUM;
              gridStyle = 0;
              keepFull = true;
              larger = false;
              faceExposure = true;
              camera.setFaceDetection(true);
              savePrefs();
              layoutFrame();
              updateLabels();
              camera.update(snapshot());
              showSettings();
              message("Preferences reset. Your photos are safe.");
            });
    reset.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    body.addView(reset, new LinearLayout.LayoutParams(-1, dp(43)));
    LinearLayout note = column();
    note.setPadding(dp(17), dp(16), dp(17), dp(11));
    note.setBackground(bg(0xffe7ebdf, 10, 0, 0));
    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
    np.topMargin = dp(17);
    body.addView(note, np);
    TextView label = text("A NOTE FROM GRAIN", 9, 0xff60714d);
    label.setLetterSpacing(.12f);
    label.setGravity(Gravity.START);
    note.addView(label);
    note.addView(
        paragraph(
            "A real camera. ISP looks are experimental and your phone may override them. The"
                + " Fujifilm recipes are developed from this phone’s RAW with Fujifilm’s own LUTs,"
                + " Sony s709 with Sony’s; the stills tone model is fitted on public Fujifilm"
                + " samples and still needs testing on your phone. Every photo keeps a receipt."));
    TextView signature = text("grain. / Made for the feeling of it.", 11, MUTED);
    signature.setTypeface(Typeface.SERIF);
    LinearLayout.LayoutParams sig = new LinearLayout.LayoutParams(-1, dp(56));
    sig.topMargin = dp(12);
    body.addView(signature, sig);
  }

  private void takePhoto() {
    dismissCameraTool();
    if (!powerOn) return;
    if (counting) {
      cancelTimer();
      return;
    }
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      permissionAsked = false;
      openCamera();
      return;
    }
    if (!ready && !busy) {
      ready = false;
      camera.close();
      main.postDelayed(this::openCamera, 250);
      message("Reconnecting camera…");
      return;
    }
    if (!ready || busy) {
      message(
          ready
              ? "Still saving the last moment…"
              : "Camera is not ready. Check permission or restart it.");
      return;
    }
    if (!rawRecipeReady()) return;
    if (timerSeconds > 0) {
      counting = true;
      int token = ++countdownToken;
      countdown(timerSeconds, token);
      return;
    }
    captureNow();
  }

  /** RAW films need RAW on this camera and the imported LUT; explain instead of failing. */
  private boolean rawRecipeReady() {
    Recipe r = Recipe.ALL[recipe];
    if (!r.raw) return true;
    if (!camera.rawAvailable()) {
      new AlertDialog.Builder(this)
          .setTitle("RAW isn’t available")
          .setMessage(
              r.name + " is developed from the sensor RAW, but: " + camera.rawUnavailableReason()
                  + ". Diagnostics has the full camera report.")
          .setPositiveButton("OK", null)
          .setNeutralButton("Diagnostics", (d, w) -> showDiagnostics())
          .show();
      return false;
    }
    if (!r.fuji.apple() && !luts.has(r.fuji.filmSimulation)) {
      boolean sony = r.fuji.sony();
      new AlertDialog.Builder(this)
          .setTitle("One-time setup")
          .setMessage(
              sony
                  ? r.name + " uses Sony’s official s709 LUT. Download Sony’s free s709Cubes.zip"
                      + " (about 0.5 MB), then import the zip here. It stays inside Grain."
                  : r.name + " uses Fujifilm’s official " + r.fuji.filmLabel + " LUT. Download Fujifilm’s"
                      + " free LUT pack (GFX ETERNA 55, about 46 MB), then import the zip here. It stays"
                      + " inside Grain.")
          .setPositiveButton("Import zip", (d, w) -> pickLuts(null))
          .setNeutralButton(
              "Download", (d, w) -> openUrl(sony ? LutLibrary.SONY_ZIP : LutLibrary.RECOMMENDED_PACK))
          .setNegativeButton("Cancel", null)
          .show();
      return false;
    }
    return true;
  }

  private void countdown(int remaining, int token) {
    if (!counting || token != countdownToken || !resumed) return;
    if (remaining == 0) {
      counting = false;
      captureNow();
      return;
    }
    shutter.setText(String.valueOf(remaining));
    shutter.setContentDescription("Cancel timer");
    back.state.countdown = String.valueOf(remaining);
    status.setText(
        isHiRes(drive)
            ? "Tripod settling (" + remaining + "s) · tap shutter to cancel"
            : "Tap shutter again to cancel");
    main.postDelayed(() -> countdown(remaining - 1, token), 1000);
  }

  private void cancelTimer() {
    if (counting) {
      counting = false;
      countdownToken++;
      if (shutter != null) {
        shutter.setText("◉");
        shutter.setContentDescription("Take photo");
      }
      if (back != null) back.state.countdown = "";
      if (status != null) status.setText("Timer cancelled · no photo taken");
    }
  }

  private void captureNow() {
    busy = true;
    stopMagnifier();
    camera.neutralPreview(false);
    neutralHeld = false;
    Recipe r = Recipe.ALL[recipe];
    bracketFilms = drive == DRIVE_FS_BKT && r.raw ? bracketSet() : null;
    if (drive == DRIVE_FS_BKT && !r.raw) {
      back.toast("FS BKT", "bracketing develops RAW films · single frame");
    }
    back.state.countdown = "";
    shutter.setText("·");
    shutter.setContentDescription("Capturing photo");
    if (drive == DRIVE_FOCUS_BKT) {
      if (camera.focusBracketingAvailable()) {
        int count = FocusMath.BKT_COUNTS[Math.max(0, Math.min(FocusMath.BKT_COUNTS.length - 1, bktFramesIndex))];
        focusBracketPlan = FocusMath.bracketFractions(camera.currentFocusFraction(), count, bktStepMode);
        focusBracketStep = 0;
        focusBracketStartNs = System.nanoTime();
        pushOsd();
        runFocusBracketStep();
        return;
      }
      back.toast("FOCUS BKT", "manual focus distance unavailable · single frame");
    }
    if (isHiRes(drive)) {
      if (r.raw && camera.rawAvailable()) {
        hiResActive = true;
        hiResStep = 0;
        hiResPhaseMask = 0;
        hiResPhases = 0;
        hiResShiftPx = 0;
        hiResMode = drive;
        hiResStartNs = System.nanoTime();
        hiResSavedAeLock = aeLock;
        hiResAccumulator = null;
        hiResAnchorDng = null;
        hiResAnchorPhoneJpeg = null;
        pushOsd();
        runHiResStep();
        return;
      }
      back.toast(hiResLabel(drive), "develops from sensor RAW · shooting single frame");
    }
    status.setText(
        r.raw
            ? (bracketFilms != null && bracketFilms.length > 1
                ? "Capturing RAW for " + bracketFilms.length + " films…"
                : "Capturing RAW + phone JPEG…")
            : "Capturing full-resolution JPEG…");
    shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    playShutterSound();
    camera.capture(snapshot());
  }

  private void runFocusBracketStep() {
    if (!busy || focusBracketPlan == null || !resumed || !powerOn || camera == null) {
      abortFocusBracket();
      return;
    }
    int step = focusBracketStep, total = focusBracketPlan.length;
    status.setText("Focus bracket " + (step + 1) + " of " + total + " · positioning lens…");
    camera.prepareBracketStep(
        focusBracketPlan[step],
        step,
        total,
        () -> {
          if (!busy || focusBracketPlan == null || !resumed || !powerOn || camera == null) {
            abortFocusBracket();
            return;
          }
          status.setText("Focus bracket " + (step + 1) + " of " + total + " · capturing…");
          if (step == 0) shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
          playShutterSound();
          camera.capture(snapshot());
        });
  }

  private void abortFocusBracket() {
    focusBracketPlan = null;
    focusBracketStep = 0;
    if (camera != null) camera.clearBracket();
    saving = false;
    busy = false;
    if (shutter != null) {
      shutter.setText("◉");
      shutter.setContentDescription("Take photo");
    }
    pushOsd();
  }

  /**
   * Lights the sub-pixel phase cells the frames have covered so far, for the on-screen guide: the
   * merge can only reach sqrt(frames) x linear when the nine frames land on nine different phases.
   */
  private void updateHiResGuide(int[] cells, float shiftPx) {
    int mask = 0;
    for (int cell : cells) if (cell >= 0 && cell < 9) mask |= 1 << cell;
    hiResPhaseMask = mask;
    hiResPhases = Integer.bitCount(mask);
    hiResShiftPx = shiftPx;
    pushOsd();
  }

  private void runHiResStep() {
    if (!busy || !hiResActive || !resumed || !powerOn || camera == null) {
      abortHiRes();
      return;
    }
    final int step = hiResStep;
    final int frames = hiResFramesFor(hiResMode);
    back.state.countdown = (step + 1) + "/" + frames;
    pushOsd();
    Runnable fireCapture =
        () -> {
          if (!busy || !hiResActive || !resumed || !powerOn || camera == null) {
            abortHiRes();
            return;
          }
          status.setText(
              (step == 0 ? "ON A TRIPOD · HOLD STEADY · " : "")
                  + "HI-RES "
                  + (step + 1)
                  + "/"
                  + frames
                  + " · capturing RAW sub-pixel shift…");
          playShutterSound();
          camera.capture(snapshot());
        };
    if (step == 0) {
      // The exposure is held for the whole sequence: nine frames averaged together cannot afford
      // the exposure moving between them. The preview follows the lock so the framing does not
      // breathe between the shots either.
      camera.setAeLock(true);
      camera.update(snapshot());
      if (camera.focusBracketingAvailable()) {
        camera.prepareBracketStep(camera.currentFocusFraction(), 0, frames, fireCapture);
      } else {
        fireCapture.run();
      }
    } else {
      pulseOisMicroShift(step, frames);
      main.postDelayed(fireCapture, 38);
    }
  }

  /**
   * Pulses a calibrated 14ms micro-vibration prior to frames 2..4 so the spring-mounted OIS lens
   * element displaces by ~0.35-0.75 px even when locked rigidly on a tripod.
   */
  private void pulseOisMicroShift(int step, int frames) {
    try {
      Vibrator vib = getSystemService(Vibrator.class);
      if (vib != null && vib.hasVibrator()) {
        vib.vibrate(VibrationEffect.createOneShot(14, oisAmplitude(step, frames)));
      } else if (shutter != null) {
        shutter.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * The vibration amplitude for one step. Four frames use the three amplitudes that were measured
   * to work. Nine frames walk a wider ladder, because the merge resolves sqrt(N) x linear and it
   * can only do that if the nine frames land on nine different sub-pixel phases - so the
   * amplitudes have to spread the frames out, not just make them wobble.
   */
  static int oisAmplitude(int step, int frames) {
    return DriveModes.oisAmplitude(step, frames);
  }

  private void abortHiRes() {
    hiResActive = false;
    hiResStep = 0;
    hiResAccumulator = null;
    if (hiResAnchorDng != null && hiResAnchorDng.exists() && !hiResAnchorDng.delete()) {
      android.util.Log.w("Grain", "Temp HiRes DNG kept");
    }
    hiResAnchorDng = null;
    hiResAnchorPhoneJpeg = null;
    if (camera != null) {
      camera.clearBracket();
      camera.setAeLock(hiResSavedAeLock);
    }
    if (back != null) back.state.countdown = "";
    saving = false;
    busy = false;
    if (shutter != null) {
      shutter.setText("◉");
      shutter.setContentDescription("Take photo");
    }
    pushOsd();
  }

  private void playShutterSound() {
    boolean required = Build.VERSION.SDK_INT >= 33 && MediaActionSound.mustPlayShutterSound();
    if (sound != null && (sounds || required)) sound.play(MediaActionSound.SHUTTER_CLICK);
  }

  /** Film simulation bracketing: the film on the dial and the next two RAW films with a LUT. */
  private Recipe[] bracketSet() {
    List<Recipe> out = new ArrayList<>();
    out.add(Recipe.ALL[recipe]);
    int start = Math.max(0, indexOfFilm(recipe));
    for (int k = 1; k < dialFilms.length && out.size() < 3; k++) {
      Recipe f = dialFilms[(start + k) % dialFilms.length];
      if (f.raw && f.id != recipe && (f.fuji.apple() || luts.has(f.fuji.filmSimulation))) out.add(f);
    }
    return out.toArray(new Recipe[0]);
  }

  @Override
  public void ready(JSONObject caps, int w, int h) {
    capabilities = caps;
    // The AF area modes are not all available on every phone. MULTI is a grid, so it is only real
    // when the HAL accepts a full 3x3 set of AF regions (CONTROL_MAX_REGIONS_AF); on this Redmi that
    // number is 1, and the honest menu leaves MULTI out rather than offering a control that quietly
    // does the same thing as CENTER. FACE needs the HAL to run face detection at all.
    afAreas.clear();
    afAreas.add(GrainCamera.AF_SINGLE);
    org.json.JSONObject focus = caps.optJSONObject("focus");
    if (focus != null) {
      if (focus.optInt("maxRegionsAf", 1) >= 9) afAreas.add(GrainCamera.AF_MULTI);
      afAreas.add(GrainCamera.AF_ZONE);
      afAreas.add(GrainCamera.AF_CENTER);
      org.json.JSONArray faceModes = focus.optJSONArray("faceDetectModes");
      if (faceModes != null) {
        boolean detects = false;
        for (int i = 0; i < faceModes.length(); i++) if (faceModes.optInt(i) > 0) detects = true;
        if (detects) afAreas.add(GrainCamera.AF_FACE);
      }
    }
    if (!afAreas.contains(afArea)) afArea = afAreas.get(0);
    if (!caps.optBoolean("flashAvailable", false) && flashMode != ExposureMath.FLASH_OFF) {
      flashMode = ExposureMath.FLASH_OFF;
      if (back != null) back.setFlashMode(flashMode);
    }
    sourceWidth = w;
    sourceHeight = h;
    ready = true;
    double aperture = caps.optDouble("aperture", 0);
    long equivalent = caps.optLong("equivalentFocalMm", 0);
    if (back != null)
      back.setLens(
          (equivalent > 0 ? equivalent + "mm" : "")
              + (aperture > 0 ? String.format(Locale.US, "  1:%.1f", aperture) : ""));
    updateDials();
    status.setText(
        "JPEG " + caps.optString("selectedJpeg")
            + (caps.optBoolean("rawImplemented") ? " · RAW " + caps.optString("rawSelected") : " · no RAW"));
    updateLabels();
    camera.update(snapshot());
  }

  @Override
  public void error(String text) {
    if (focusBracketPlan != null) {
      focusBracketPlan = null;
      focusBracketStep = 0;
      if (camera != null) camera.clearBracket();
    }
    if (hiResActive) {
      hiResActive = false;
      hiResStep = 0;
      hiResAccumulator = null;
      if (hiResAnchorDng != null && hiResAnchorDng.exists() && !hiResAnchorDng.delete()) {
        android.util.Log.w("Grain", "Temp HiRes DNG kept");
      }
      hiResAnchorDng = null;
      hiResAnchorPhoneJpeg = null;
      if (camera != null) {
        camera.clearBracket();
        camera.setAeLock(hiResSavedAeLock);
      }
      if (back != null) back.state.countdown = "";
    }
    saving = false;
    busy = false;
    shutter.setText("◉");
    shutter.setContentDescription("Take photo");
    status.setText("Camera needs attention · shutter reconnects");
    ready = false;
    pushOsd();
    message(text);
  }

  @Override
  public void status(String text) {
    status.setText(text);
  }

  @Override
  public void captured(byte[] jpeg, GrainCamera.Snapshot settings, JSONObject receipt) {
    if (isDestroyed() || isFinishing()) return;
    saving = true;
    int fStep = focusBracketPlan != null ? focusBracketStep : -1;
    int fTotal = focusBracketPlan != null ? focusBracketPlan.length : 0;
    status.setText(
        fTotal > 0
            ? "Saving focus bracket " + (fStep + 1) + " of " + fTotal + "…"
            : "Saving your moment…");
    files.execute(
        () -> {
          try {
            if (fTotal > 0) receipt.put("focusBracketSummary", (fStep + 1) + " of " + fTotal);
            PhotoStore.Photo photo =
                store.save(jpeg, settings.ratio, settings.zoom, settings.keepFull, receipt);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  if (focusBracketPlan != null) {
                    focusBracketStep++;
                    if (focusBracketStep < focusBracketPlan.length && resumed && powerOn) {
                      saving = false;
                      runFocusBracketStep();
                      return;
                    }
                    int doneCount = focusBracketPlan.length;
                    focusBracketPlan = null;
                    focusBracketStep = 0;
                    camera.clearBracket();
                    saving = false;
                    busy = false;
                    shutter.setText("◉");
                    shutter.setContentDescription("Take photo");
                    status.setText(
                        photo.metadata.optInt("width")
                            + " × "
                            + photo.metadata.optInt("height")
                            + " · "
                            + doneCount
                            + " focus planes saved");
                    updateThumb();
                    syncMagnifier();
                    message(
                        doneCount
                            + " focus-bracketed frames saved ("
                            + FocusMath.BKT_STEP_NAMES[bktStepMode].toLowerCase(Locale.ROOT)
                            + " step)");
                    return;
                  }
                  saving = false;
                  busy = false;
                  shutter.setText("◉");
                  shutter.setContentDescription("Take photo");
                  status.setText(
                      photo.metadata.optInt("width")
                          + " × "
                          + photo.metadata.optInt("height")
                          + " · saved privately");
                  updateThumb();
                  syncMagnifier();
                  boolean warn =
                      receipt.has("ispWarning")
                          || receipt.optJSONObject("requested").optJSONArray("warnings").length()
                              > 0;
                  message(
                      photo.metadata.optBoolean("recoveredFullFrame")
                          ? "Crop failed; your full-frame capture was recovered. Check its receipt."
                          : receipt.has("focusWarning")
                              ? "Saved, but the camera reported not focused. Tap the subject and retry."
                          : warn
                              ? "Saved. ISP limitations are recorded in this photo’s receipt."
                              : "A moment, kept.");
                });
          } catch (Exception e) {
            runOnUiThread(() -> error("Could not save photo: " + e.getMessage()));
          }
        });
  }

  @Override
  public void capturedRaw(
      GrainCamera.RawCapture capture, GrainCamera.Snapshot settings, JSONObject receipt) {
    if (isDestroyed() || isFinishing()) {
      if (capture.dng != null && !capture.dng.delete()) android.util.Log.w("Grain", "Temp DNG kept");
      return;
    }
    saving = true;
    Recipe r = Recipe.ALL[settings.recipe];
    FujiRecipe film = settings.fuji != null ? settings.fuji : r.fuji;
    boolean tuned = !film.sameSettings(r.fuji);
    String filmName = r.name + (tuned ? " (tuned)" : "");
    boolean meterFaces = faceExposure;
    // Film simulation bracketing develops the one RAW as up to three films (the DNG and the phone
    // JPEG are kept with the first). Their tuning is read here, on the main thread.
    Recipe[] bracket = bracketFilms != null && bracketFilms.length > 1 && bracketFilms[0].id == r.id ? bracketFilms : new Recipe[] {r};
    bracketFilms = null;
    FujiRecipe[] bracketTuning = new FujiRecipe[bracket.length];
    int[] bracketStrength = new int[bracket.length];
    for (int i = 0; i < bracket.length; i++) {
      bracketTuning[i] = i == 0 ? film : tuning.effective(bracket[i]);
      bracketStrength[i] = strengths[bracket[i].id];
    }
    // Manual exposure: development shows the exposure's difference from the meter instead of the
    // EV dial, so a darker setting gives a darker photo (GrainCamera.manualExposure).
    JSONObject requested = receipt.optJSONObject("requested");
    JSONObject manual = requested == null ? null : requested.optJSONObject("manual");
    float developEv =
        manual != null && manual.has("developEv")
            ? (float) Math.max(-4, Math.min(4, manual.optDouble("developEv")))
            : settings.ev;
    if (hiResActive) {
      final int hStep = hiResStep;
      final int hFrames = hiResFramesFor(hiResMode);
      status.setText(
          hStep < hFrames - 1
              ? "HI-RES " + (hStep + 1) + "/" + hFrames + " · aligning RAW sub-pixels…"
              : "HI-RES "
                  + hFrames
                  + "/"
                  + hFrames
                  + " · merging "
                  + hiResMegapixels(hiResMode)
                  + "MP & developing "
                  + filmName
                  + "…");
      files.execute(
          () -> {
            try {
              if (hStep == 0) {
                hiResTargetPixelsUsed = hiResEffectiveTargetPixels();
                hiResAccumulator =
                    new HiResMerger.Accumulator(
                        capture.frame, hiResTargetPixelsUsed, hiResMaxScale(hiResMode));
                hiResAnchorDng = capture.dng;
                hiResAnchorPhoneJpeg = keepRawFiles ? capture.phoneJpeg : null;
              } else if (capture.dng != null && capture.dng.exists() && !capture.dng.delete()) {
                android.util.Log.w("Grain", "Temp HiRes step DNG kept");
              }
              hiResAccumulator.addFrame(capture.frame);
              final int[] cells = hiResAccumulator.phaseCells();
              final float shiftPx = (float) hiResAccumulator.lastShiftPx();
              runOnUiThread(() -> updateHiResGuide(cells, shiftPx));
              if (hStep < hFrames - 1) {
                runOnUiThread(
                    () -> {
                      if (isDestroyed() || !hiResActive || !resumed || !powerOn) {
                        abortHiRes();
                        return;
                      }
                      hiResStep++;
                      saving = false;
                      runHiResStep();
                    });
                return;
              }

              // All the RAW frames are accumulated: finalize the merged RawFrame & free the
              // accumulator before developing.
              long mergeStart = System.nanoTime();
              String shifts = hiResAccumulator.shiftsSummary();
              int[] phaseCells = hiResAccumulator.phaseCells();
              int phasesFilled = hiResAccumulator.phasesFilled();
              RawFrame mergedFrame = hiResAccumulator.finish();
              hiResAccumulator = null;
              long mergeMs = (System.nanoTime() - mergeStart) / 1_000_000;

              File keptDng = hiResAnchorDng;
              byte[] keptPhone = hiResAnchorPhoneJpeg;
              hiResAnchorDng = null;
              hiResAnchorPhoneJpeg = null;

              long mergedSeed = mergedFrame.exposureNs ^ System.nanoTime();
              GrainCamera.RawCapture mergedCapture =
                  new GrainCamera.RawCapture(
                      mergedFrame,
                      keptPhone,
                      keptDng,
                      capture.orientationDegrees,
                      capture.uprightDegrees);
              // Drop the outer reference so RawDevelopment can free the 225 MB merge frame before JPEG encode.
              mergedFrame = null;

              CubeLut lut = film.apple() ? null : luts.load(film.filmSimulation);
              long devStart = System.nanoTime();
              RawDevelopment.Output out =
                  RawDevelopment.develop(
                      this,
                      mergedCapture,
                      film,
                      tuned,
                      tuning.appleEffective(film),
                      lut,
                      developEv,
                      mergedSeed,
                      meterFaces,
                      true);
              long devMs = (System.nanoTime() - devStart) / 1_000_000;

              receipt.put(
                  "hiResMode",
                  hiResMode == DRIVE_HIRES_112
                            ? "tripod_9shot_112mp"
                            : hiResMode == DRIVE_HIRES_48
                                ? "tripod_4shot_48mp"
                                : "tripod_4shot_36mp");
              receipt.put("hiResMegapixels", hiResMegapixels(hiResMode));
              receipt.put("hiResTargetMegapixels", (int) (hiResTargetPixelsUsed / 1_000_000L));
              receipt.put("hiResFrames", hFrames);
              receipt.put("hiResPhases", new org.json.JSONArray(phaseCells));
              receipt.put("hiResPhasesFilled", phasesFilled);
              receipt.put("hiResShifts", shifts);
              receipt.put("hiResMergeMs", mergeMs);
              if (manual != null) receipt.put("developEv", developEv);
              receipt.put("development", out.stats.put("totalMs", devMs));

              PhotoStore.Photo saved;
              try {
                saved =
                    store.saveFile(
                        out.jpegFile,
                        settings.ratio,
                        settings.zoom,
                        settings.keepFull,
                        receipt,
                        keptDng,
                        keptPhone);
              } finally {
                if (out.jpegFile.exists()) out.jpegFile.delete();
              }
              if (keptDng != null && keptDng.exists() && !keptDng.delete()) {
                android.util.Log.w("Grain", "Temp anchor DNG kept");
              }
              long totalSecMs = (System.nanoTime() - hiResStartNs) / 1_000_000;

              runOnUiThread(
                  () -> {
                    if (isDestroyed()) return;
                    hiResActive = false;
                    hiResStep = 0;
                    if (camera != null) {
                      camera.clearBracket();
                      camera.setAeLock(hiResSavedAeLock);
                    }
                    back.state.countdown = "";
                    saving = false;
                    busy = false;
                    shutter.setText("◉");
                    shutter.setContentDescription("Take photo");
                    int w = saved.metadata.optInt("width"), h = saved.metadata.optInt("height");
                    double mp = (w * (double) h) / 1_000_000.0;
                    status.setText(
                        String.format(
                            Locale.US,
                            "%d × %d (%.1f MP) · %s · %d-RAW Hi-Res (%.1f s)",
                            w,
                            h,
                            mp,
                            filmName,
                            hFrames,
                            totalSecMs / 1000.0));
                    updateThumb();
                    syncMagnifier();
                    showSnackbar(
                        String.format(
                            Locale.US,
                            "%.1f MP Hi-Res saved in PLAY (%d RAWs · %s)",
                            mp,
                            hFrames,
                            filmName),
                        "SAVE",
                        () -> export(saved, false, false));
                  });
            } catch (Throwable e) {
              if (capture.dng != null && capture.dng.exists() && !capture.dng.delete()) {
                android.util.Log.w("Grain", "Temp HiRes DNG kept");
              }
              String why =
                  e instanceof OutOfMemoryError ? "not enough memory" : String.valueOf(e.getMessage());
              runOnUiThread(
                  () -> {
                    abortHiRes();
                    status.setText("Hi-Res merge failed · camera still ready");
                    syncMagnifier();
                    message("Hi-Res merge failed: " + why);
                  });
            }
          });
      return;
    }
    int fStep = focusBracketPlan != null ? focusBracketStep : -1;
    int fTotal = focusBracketPlan != null ? focusBracketPlan.length : 0;
    status.setText(
        fTotal > 0
            ? "Developing focus bracket " + (fStep + 1) + " of " + fTotal + " · " + filmName + "…"
            : bracket.length > 1
                ? "Developing " + bracket.length + " films from one RAW…"
                : "Developing " + filmName + " from RAW…");
    files.execute(
        () -> {
          try {
            long t = System.nanoTime();
            if (fTotal > 0) receipt.put("focusBracketSummary", (fStep + 1) + " of " + fTotal);
            PhotoStore.Photo photo = null;
            for (int i = 0; i < bracket.length; i++) {
              FujiRecipe f = bracketTuning[i];
              boolean fTuned = !f.sameSettings(bracket[i].fuji);
              CubeLut lut = f.apple() ? null : luts.load(f.filmSimulation);
              JSONObject frameReceipt = i == 0 ? receipt : new JSONObject(receipt.toString());
              if (bracket.length > 1) {
                frameReceipt.put("bracket", (i + 1) + " of " + bracket.length);
                if (i > 0) frameReceipt.put("recipe", bracket[i].json(bracketStrength[i], f));
              }
              if (manual != null) frameReceipt.put("developEv", developEv);
              long start = System.nanoTime();
              RawDevelopment.Output out =
                  RawDevelopment.develop(
                      this,
                      capture,
                      f,
                      fTuned,
                      tuning.appleEffective(f),
                      lut,
                      developEv,
                      capture.frame.exposureNs ^ System.nanoTime(),
                      meterFaces,
                      i == bracket.length - 1);
              frameReceipt.put(
                  "development", out.stats.put("totalMs", (System.nanoTime() - start) / 1_000_000));
              PhotoStore.Photo saved;
              try {
                saved =
                    store.saveFile(
                        out.jpegFile,
                        settings.ratio,
                        settings.zoom,
                        settings.keepFull,
                        frameReceipt,
                        i == 0 ? capture.dng : null,
                        i == 0 && keepRawFiles ? capture.phoneJpeg : null);
              } finally {
                if (out.jpegFile.exists()) out.jpegFile.delete();
              }
              if (i == 0) photo = saved;
              int done = i + 1;
              if (bracket.length > 1 && done < bracket.length)
                runOnUiThread(() -> status.setText("Developing film " + (done + 1) + " of " + bracket.length + "…"));
            }
            long ms = (System.nanoTime() - t) / 1_000_000;
            PhotoStore.Photo first = photo;
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  if (focusBracketPlan != null) {
                    focusBracketStep++;
                    if (focusBracketStep < focusBracketPlan.length && resumed && powerOn) {
                      saving = false;
                      runFocusBracketStep();
                      return;
                    }
                    int doneCount = focusBracketPlan.length;
                    long totalBurstMs = (System.nanoTime() - focusBracketStartNs) / 1_000_000;
                    focusBracketPlan = null;
                    focusBracketStep = 0;
                    camera.clearBracket();
                    saving = false;
                    busy = false;
                    shutter.setText("◉");
                    shutter.setContentDescription("Take photo");
                    status.setText(
                        first.metadata.optInt("width")
                            + " × "
                            + first.metadata.optInt("height")
                            + " · "
                            + doneCount
                            + " RAW focus planes ("
                            + String.format(Locale.US, "%.1f s", totalBurstMs / 1000.0)
                            + ")");
                    updateThumb();
                    syncMagnifier();
                    message(
                        doneCount
                            + " RAW focus-bracketed frames developed ("
                            + filmName
                            + ")");
                    return;
                  }
                  saving = false;
                  busy = false;
                  shutter.setText("◉");
                  shutter.setContentDescription("Take photo");
                  status.setText(
                      first.metadata.optInt("width") + " × " + first.metadata.optInt("height")
                          + " · " + filmName + " · developed in " + String.format(Locale.US, "%.1f s", ms / 1000.0));
                  updateThumb();
                  syncMagnifier();
                  message(
                      receipt.has("focusWarning")
                          ? "Saved, but the camera reported not focused. Tap the subject and retry."
                          : bracket.length > 1
                              ? bracket.length + " films developed from one RAW" + (first.dng != null ? " · DNG kept with the first" : "")
                              : filmName + " developed from RAW" + (first.dng != null ? " · DNG kept" : ""));
                });
          } catch (Throwable e) {
            if (capture.dng != null && capture.dng.exists() && !capture.dng.delete())
              android.util.Log.w("Grain", "Temp DNG kept");
            String why = e instanceof OutOfMemoryError ? "not enough memory" : String.valueOf(e.getMessage());
            runOnUiThread(
                () -> {
                  if (focusBracketPlan != null) {
                    focusBracketPlan = null;
                    focusBracketStep = 0;
                    if (camera != null) camera.clearBracket();
                  }
                  saving = false;
                  busy = false;
                  shutter.setText("◉");
                  shutter.setContentDescription("Take photo");
                  status.setText("RAW development failed · camera still ready");
                  syncMagnifier();
                  message("RAW development failed: " + why);
                });
          }
        });
  }

  private void openCamera() {
    if ("onboarding".equals(page)) return;
    if (!resumed || !powerOn || !texture.isAvailable() || camera == null) return;
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      if (!permissionAsked) {
        permissionAsked = true;
        requestPermissions(new String[] {Manifest.permission.CAMERA}, 7);
      } else status.setText("Camera permission needed · tap shutter for help");
      return;
    }
    camera.open(front, larger);
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] p, int[] result) {
    super.onRequestPermissionsResult(code, p, result);
    if (code == 7) {
      boolean granted = result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED;
      if ("onboarding".equals(page)) {
        showOnboarding(granted && onboardingStep == 2 ? 3 : onboardingStep);
        return;
      }
      if (granted) openCamera();
      else
        new AlertDialog.Builder(this)
            .setTitle("A camera needs a camera")
            .setMessage(
                "Allow camera access to take photos. No microphone or broad photo-library"
                    + " permission is requested.")
            .setPositiveButton(
                "Open app settings",
                (d, w) ->
                    startActivity(
                        new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))))
            .setNegativeButton("Not now", null)
            .show();
    }
  }

  private final Runnable startupUpdateTask = () -> checkForUpdates(false);

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (startupFailed) {
      if (preferences == null) preferences = getSharedPreferences("grain", MODE_PRIVATE);
      main.removeCallbacks(startupUpdateTask);
      main.postDelayed(startupUpdateTask, 400);
      return;
    }
    immersive();
    // The preview surface is rebuilt while the activity comes back; give it a moment.
    settlePreview();
    if (orientationListener != null && orientationListener.canDetectOrientation())
      orientationListener.enable();
    if (sensors == null) {
      sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
      if (sensors != null) gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
    }
    if (sensors != null && gravity != null)
      sensors.registerListener(pitchListener, gravity, SensorManager.SENSOR_DELAY_GAME);
    if (remoteOn && remote == null && RemoteStream.localIp() != null) startRemote();
    if (texture != null && texture.isAvailable()) openCamera();
    readBattery();
    main.removeCallbacks(histogramTask);
    main.postDelayed(histogramTask, 500);
    if (peaking) startPeaking();
    syncMagnifier();
    updateThumb();
    if (notifyUpdates
        && !"1".equals(System.getenv("GRAIN_SCREENS"))
        && (lastUpdateCheckMs == 0L
            || SystemClock.elapsedRealtime() - lastUpdateCheckMs > 15 * 60_000L)) {
      main.removeCallbacks(startupUpdateTask);
      // Early: if the app dies a second after opening, a later check would never happen and the
      // user would be stuck with a crashing build.
      main.postDelayed(startupUpdateTask, 400);
    }
    if (!crashShown) {
      crashShown = true;
      // If the last run ended in a crash, show the report so the user can send it.
      main.postDelayed(
          () -> {
            if (!isFinishing() && !isDestroyed()) CrashLog.showPending(this);
          },
          500);
    }
  }

  @Override
  protected void onPause() {
    if (startupFailed) {
      resumed = false;
      main.removeCallbacks(startupUpdateTask);
      super.onPause();
      return;
    }
    if (orientationListener != null) orientationListener.disable();
    if (sensors != null) sensors.unregisterListener(pitchListener);
    stopRemote();
    if (remoteOn) remote = null;
    main.removeCallbacks(histogramTask);
    main.removeCallbacks(peakingTask);
    main.removeCallbacks(startupUpdateTask);
    stopMagnifier();
    if (halfPressed) releaseHalf();
    neutralHeld = false;
    dismissCameraTool();
    resumed = false;
    cancelTimer();
    if (focusBracketPlan != null) {
      focusBracketPlan = null;
      focusBracketStep = 0;
    }
    if (hiResActive) {
      hiResActive = false;
      hiResStep = 0;
      hiResAccumulator = null;
    }
    if (camera != null) {
      camera.clearBracket();
      camera.neutralPreview(false);
      camera.close();
    }
    ready = false;
    if (busy && !saving) {
      busy = false;
      shutter.setText("◉");
      shutter.setContentDescription("Take photo");
    }
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    AppUpdater.setStatusListener(null);
    AppUpdater.setConfirmationLauncher(null);
    if (camera != null) camera.close();
    if (sound != null) sound.release();
    main.removeCallbacksAndMessages(null);
    files.shutdown();
    network.shutdownNow();
    super.onDestroy();
  }

  @Override
  public boolean onKeyDown(int code, KeyEvent event) {
    if (startupFailed) return super.onKeyDown(code, event);
    if ((code == KeyEvent.KEYCODE_VOLUME_UP
            || code == KeyEvent.KEYCODE_VOLUME_DOWN
            || code == KeyEvent.KEYCODE_CAMERA)
        && "camera".equals(page)) {
      if (event.getRepeatCount() == 0) {
        if (!"camera".equals(mode)) closeOverlays();
        else takePhoto();
      }
      return true;
    }
    return super.onKeyDown(code, event);
  }

  private void navigateBack() {
    if ("camera".equals(page) && !"camera".equals(mode)) {
      closeOverlays();
      return;
    }
    if ("camera".equals(page) && evf) {
      setEvf(false);
      return;
    }
    if (counting) cancelTimer();
    else if ("onboarding".equals(page)) {
      if (onboardingStep > 0) showOnboarding(onboardingStep - 1);
      else if (preferences.getBoolean("onboardingDone", false)) closeOnboarding();
      else finish();
    } else if ("photos".equals(page) && gallerySelectMode) {
      gallerySelectMode = false;
      gallerySelectedIds.clear();
      showGallery();
    } else if ("detail".equals(page)) showGallery();
    else if (!"camera".equals(page)) closePanel();
    else finish();
  }

  private void updateThumb() {
    final PhotoStore s = store;
    if (s == null) return;
    files.execute(
        () -> {
          int count = s.list().size();
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                frameCount = count;
                pushOsd();
              });
        });
  }

  private void openPanel(String title, String subtitle) {
    cancelTimer();
    stopMagnifier();
    dismissCameraTool();
    closePanel();
    page = "photos";
    panel = column();
    panel.setBackgroundColor(PAPER);
    panel.setPadding(0, safeTop, 0, safeBottom);
    root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout head = row();
    head.setPadding(dp(23), dp(9), dp(23), dp(7));
    panel.addView(head, new LinearLayout.LayoutParams(-1, dp(77)));
    LinearLayout titles = column();
    TextView heading = text(title, 26, INK);
    heading.setTypeface(Typeface.SERIF);
    heading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    titles.addView(heading);
    TextView sub = text(subtitle, 8, MUTED);
    sub.setLetterSpacing(.12f);
    sub.setPadding(0, dp(6), 0, 0);
    sub.setGravity(Gravity.START);
    titles.addView(sub);
    head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    TextView close =
        iconButton(
            "close",
            INK,
            13,
            () -> {
              if ("detail".equals(page)) showGallery();
              else closePanel();
            });
    close.setPadding(dp(8), 0, 0, 0);
    close.setMinHeight(0);
    close.setBackground(bg(PAPER, 30, LINE, 1));
    close.setContentDescription("detail".equals(page) ? "Back to photos" : "Back to camera");
    head.addView(close, size(30, 30));
    View line = new View(this);
    line.setBackgroundColor(LINE);
    panel.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    panelScroll = new ScrollView(this);
    panelScroll.setVerticalScrollBarEnabled(false);
    panel.addView(panelScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    syncWindowColorMode();
  }

  private void syncWindowColorMode() {
    Window w = getWindow();
    if (w == null) return;
    boolean hdrView = "play".equals(mode) || panel != null;
    int wanted =
        (hdrView && Build.VERSION.SDK_INT >= 34)
            ? android.content.pm.ActivityInfo.COLOR_MODE_HDR
            : android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT;
    try {
      if (w.getColorMode() != wanted) w.setColorMode(wanted);
    } catch (Throwable ignored) {
    }
  }

  private void setDarkSystemBars(boolean dark) {
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController c = getWindow().getInsetsController();
      if (c != null) {
        int mask =
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        c.setSystemBarsAppearance(dark ? 0 : mask, mask);
      }
    }
  }

  private void closePanel() {
    gallerySelectMode = false;
    gallerySelectedIds.clear();
    setDarkSystemBars(false);
    if (panel != null) {
      root.removeView(panel);
      panel = null;
    }
    page = "camera";
    syncWindowColorMode();
    syncMagnifier();
  }

  private void closeOnboarding() {
    preferences.edit().putBoolean("onboardingDone", true).apply();
    closePanel();
    if (resumed && texture != null && texture.isAvailable()) openCamera();
  }

  /**
   * Literal, unmistakable photographic hardware illustrations rendered natively on {@link Canvas}
   * for each onboarding step (zero monospace/terminal fonts):
   * 0 = 35mm film canister & sprocketed strip, 1 = circular engraved shutter speed dial,
   * 2 = rangefinder camera + privacy lock badge, 3 = full two-tone rangefinder camera body.
   */
  private View onboardingSculpture(int step, boolean permGranted) {
    return new View(this) {
      final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);

      @Override
      protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w * .5f, cy = h * .5f;
        p.setShader(null);
        t.setAntiAlias(true);
        t.setTypeface(Typeface.SANS_SERIF);

        if (step == 0) {
          // Step 1 ("Shoot like film."): Classic 35mm Film Canister + Perforated Film Strip
          float totalW = dp(204);
          float ox = cx - totalW * .5f;
          float canL = ox, canW = dp(68), canH = dp(116);
          float canT = cy - canH * .5f + dp(6);

          // Perforated 35mm film leader strip extending right
          float stripL = canL + canW - dp(2);
          float stripR = ox + totalW;
          float stripT = canT + dp(24), stripB = stripT + dp(72);
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff2a201a);
          c.drawRoundRect(stripL, stripT, stripR, stripB, dp(10), dp(10), p);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(dp(1.2f));
          p.setColor(0xff4a3a2e);
          c.drawRoundRect(stripL, stripT, stripR, stripB, dp(10), dp(10), p);

          // Sprocket holes (top & bottom rows)
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff111210);
          for (float hx = stripL + dp(12); hx < stripR - dp(14); hx += dp(15)) {
            c.drawRoundRect(hx, stripT + dp(5), hx + dp(6.5f), stripT + dp(9.5f), dp(1.5f), dp(1.5f), p);
            c.drawRoundRect(hx, stripB - dp(9.5f), hx + dp(6.5f), stripB - dp(5), dp(1.5f), dp(1.5f), p);
          }

          // Two exposed analog frames inside strip
          float f1L = stripL + dp(12), fW = dp(44), fT = stripT + dp(15), fB = stripB - dp(15);
          float f2L = f1L + fW + dp(8);
          for (int fi = 0; fi < 2; fi++) {
            float fl = fi == 0 ? f1L : f2L;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xff191613);
            c.drawRoundRect(fl, fT, fl + fW, fB, dp(3), dp(3), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0xff5c4938);
            c.drawRoundRect(fl, fT, fl + fW, fB, dp(3), dp(3), p);
          }
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xa6e76736);
          c.drawCircle(f1L + dp(14), fT + dp(13), dp(6.5f), p);
          android.graphics.Path hill = new android.graphics.Path();
          hill.moveTo(f1L + dp(2), fB - dp(2));
          hill.lineTo(f1L + dp(16), fT + dp(22));
          hill.lineTo(f1L + dp(28), fT + dp(31));
          hill.lineTo(f1L + dp(36), fT + dp(24));
          hill.lineTo(f1L + fW - dp(2), fB - dp(2));
          hill.close();
          p.setColor(0x75d9a566);
          c.drawPath(hill, p);

          android.graphics.Path hill2 = new android.graphics.Path();
          hill2.moveTo(f2L + dp(2), fB - dp(2));
          hill2.lineTo(f2L + dp(16), fT + dp(18));
          hill2.lineTo(f2L + dp(30), fT + dp(30));
          hill2.lineTo(f2L + fW - dp(2), fB - dp(2));
          hill2.close();
          p.setColor(0x708ab599);
          c.drawPath(hill2, p);

          // Velvet light-trap lip
          p.setColor(0xff171815);
          c.drawRoundRect(canL + canW - dp(4), canT + dp(14), canL + canW + dp(4), canT + canH - dp(10), dp(2), dp(2), p);

          // Top spool spindle
          float spW = dp(22), spL = canL + (canW - spW) * .5f;
          p.setColor(0xff2c2e29);
          c.drawRoundRect(spL, canT - dp(16), spL + spW, canT, dp(3), dp(3), p);
          p.setColor(0xffc8c6bc);
          c.drawRoundRect(spL + dp(4), canT - dp(22), spL + spW - dp(4), canT - dp(15), dp(2), dp(2), p);

          // Canister cylinder body + warm film stock band
          p.setShader(
              new LinearGradient(
                  canL,
                  0,
                  canL + canW,
                  0,
                  new int[] {0xff2c2e29, 0xff3c3e38, 0xff1c1d1a},
                  new float[] {0f, .44f, 1f},
                  Shader.TileMode.CLAMP));
          c.drawRoundRect(canL, canT, canL + canW, canT + canH, dp(6), dp(6), p);
          p.setShader(null);
          p.setColor(ORANGE);
          c.drawRect(canL, canT + dp(26), canL + canW, canT + dp(76), p);
          p.setColor(0xffe6b96e);
          c.drawRect(canL, canT + dp(76), canL + canW, canT + dp(86), p);

          // Rolled metal top & bottom caps
          p.setShader(
              new LinearGradient(
                  canL - dp(3),
                  0,
                  canL + canW + dp(3),
                  0,
                  new int[] {0xff9e9d95, 0xffe5e3da, 0xff7c7b74},
                  new float[] {0f, .45f, 1f},
                  Shader.TileMode.CLAMP));
          c.drawRoundRect(canL - dp(3), canT - dp(3), canL + canW + dp(3), canT + dp(5), dp(4), dp(4), p);
          c.drawRoundRect(
              canL - dp(3), canT + canH - dp(5), canL + canW + dp(3), canT + canH + dp(3), dp(4), dp(4), p);
          p.setShader(null);
        } else if (step == 1) {
          // Step 2 ("Real manual controls."): Top-Down Mechanical Shutter Speed Dial
          float rDial = Math.min(Math.min(w, h) * .42f, dp(82));
          float dcy = cy + dp(4);

          // Vermilion index marker at 12 o'clock
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeCap(Paint.Cap.ROUND);
          p.setStrokeWidth(dp(2.6f));
          p.setColor(ORANGE);
          c.drawLine(cx, dcy - rDial - dp(15), cx, dcy - rDial - dp(5), p);

          // Knurled outer dial body
          p.setStyle(Paint.Style.FILL);
          p.setShader(
              new android.graphics.RadialGradient(
                  cx,
                  dcy - rDial * .2f,
                  rDial,
                  new int[] {0xff2b2d28, 0xff171815},
                  null,
                  Shader.TileMode.CLAMP));
          c.drawCircle(cx, dcy, rDial, p);
          p.setShader(null);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(dp(1.6f));
          p.setColor(0xff3a3c35);
          c.drawCircle(cx, dcy, rDial, p);

          // Perimeter knurling ticks
          p.setStrokeWidth(dp(1.2f));
          p.setColor(0xff3c3e37);
          for (int i = 0; i < 48; i++) {
            double rad = Math.toRadians(i * 7.5);
            float x1 = cx + (float) Math.cos(rad) * (rDial - dp(5));
            float y1 = dcy + (float) Math.sin(rad) * (rDial - dp(5));
            float x2 = cx + (float) Math.cos(rad) * (rDial - dp(1.2f));
            float y2 = dcy + (float) Math.sin(rad) * (rDial - dp(1.2f));
            c.drawLine(x1, y1, x2, y2, p);
          }

          p.setColor(0xff282a25);
          c.drawCircle(cx, dcy, rDial - dp(9), p);

          // Rotating shutter speed markings in proportional sans-serif
          String[] marks = {"A", "1000", "500", "250", "125", "60", "30", "15"};
          float rotDeg = -45f * Math.max(0, Math.min(3, onboardingStock));
          t.setTextAlign(Paint.Align.CENTER);
          for (int i = 0; i < marks.length; i++) {
            float ang = i * 45f + rotDeg;
            c.save();
            c.rotate(ang, cx, dcy);
            boolean isAuto = i == 0;
            t.setFakeBoldText(true);
            t.setColor(isAuto ? ORANGE : 0xfff3f0e6);
            t.setTextSize(dp(isAuto ? 13 : 10.5f));
            c.drawText(marks[i], cx, dcy - rDial + dp(29), t);
            c.restore();
          }

          // Center machined cap
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff121310);
          c.drawCircle(cx, dcy, dp(22), p);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(dp(1.4f));
          p.setColor(0xff363831);
          c.drawCircle(cx, dcy, dp(22), p);
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff232520);
          c.drawCircle(cx, dcy, dp(5.5f), p);
        } else if (step == 2) {
          // Step 3 ("Camera access, privately."): Rangefinder Camera + Privacy Lock Badge
          float bw = dp(160), bh = dp(106);
          float bl = cx - bw * .5f, bt = cy - bh * .5f;
          int badgeColor = permGranted ? 0xff4ca96b : ORANGE;

          // Shutter button + dial cap
          p.setStyle(Paint.Style.FILL);
          p.setColor(ORANGE);
          c.drawRoundRect(bl + bw - dp(44), bt - dp(8), bl + bw - dp(20), bt, dp(3), dp(3), p);
          p.setColor(0xff3a3c36);
          c.drawRoundRect(bl + bw - dp(78), bt - dp(6), bl + bw - dp(52), bt, dp(2), dp(2), p);

          // Camera body
          p.setColor(0xff1b1c19);
          c.drawRoundRect(bl, bt, bl + bw, bt + bh, dp(18), dp(18), p);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(dp(1.8f));
          p.setColor(0xff373933);
          c.drawRoundRect(bl, bt, bl + bw, bt + bh, dp(18), dp(18), p);

          // Top plate seam + viewfinder window
          p.setStrokeWidth(dp(1.3f));
          p.setColor(0xff2c2e28);
          c.drawLine(bl + dp(2), bt + dp(28), bl + bw - dp(2), bt + dp(28), p);
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff111210);
          c.drawRoundRect(bl + dp(18), bt + dp(9), bl + dp(44), bt + dp(21), dp(3), dp(3), p);
          p.setStyle(Paint.Style.STROKE);
          p.setColor(0xff454740);
          c.drawRoundRect(bl + dp(18), bt + dp(9), bl + dp(44), bt + dp(21), dp(3), dp(3), p);

          // Central camera lens
          float lcy = bt + dp(62);
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff121310);
          c.drawCircle(cx, lcy, dp(32), p);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(dp(2));
          p.setColor(0xff474a42);
          c.drawCircle(cx, lcy, dp(32), p);
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff0c0d0b);
          c.drawCircle(cx, lcy, dp(20), p);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(dp(1.4f));
          p.setColor(0xff2f312b);
          c.drawCircle(cx, lcy, dp(20), p);
          p.setStyle(Paint.Style.FILL);
          p.setColor(0x38f3f0e6);
          c.drawCircle(cx - dp(7), lcy - dp(7), dp(4), p);

          // Privacy Padlock / Granted Checkmark Badge on bottom-right corner
          float bx = bl + bw - dp(14), by = bt + bh - dp(10);
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff111210);
          c.drawCircle(bx, by, dp(25), p);
          p.setColor((badgeColor & 0x00ffffff) | 0x28000000);
          c.drawCircle(bx, by, dp(21), p);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(dp(1.8f));
          p.setColor(badgeColor);
          c.drawCircle(bx, by, dp(21), p);

          if (permGranted) {
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(2.5f));
            c.drawLine(bx - dp(8), by + dp(0.5f), bx - dp(2.5f), by + dp(6), p);
            c.drawLine(bx - dp(2.5f), by + dp(6), bx + dp(9), by - dp(5.5f), p);
          } else {
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(bx - dp(7.5f), by - dp(1), bx + dp(7.5f), by + dp(10), dp(2.5f), dp(2.5f), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.1f));
            c.drawArc(bx - dp(4.8f), by - dp(9.5f), bx + dp(4.8f), by + dp(1), 180, 180, false, p);
          }
        } else {
          // Step 4 ("Choose your camera body."): Full Two-Tone Rangefinder Camera Body
          int[] metal =
              finish == 0
                  ? new int[] {0xffefeee8, 0xffd2d1c8, 0xffb5b4ab}
                  : finish == 1
                      ? new int[] {0xff3a3b37, 0xff252623, 0xff191a17}
                      : new int[] {0xff8a8c86, 0xff696b65, 0xff50524d};

          float bw = Math.min(w - dp(24), dp(188)), bh = dp(114);
          float bl = cx - bw * .5f, bt = cy - bh * .5f + dp(4);

          // Top dial + vermilion shutter release
          p.setStyle(Paint.Style.FILL);
          p.setColor(metal[1]);
          c.drawRoundRect(bl + bw - dp(74), bt - dp(9), bl + bw - dp(44), bt + dp(2), dp(2.5f), dp(2.5f), p);
          p.setColor(ORANGE);
          c.drawRoundRect(bl + bw - dp(30), bt - dp(12), bl + bw - dp(18), bt - dp(6), dp(2), dp(2), p);
          p.setColor(metal[0]);
          c.drawRoundRect(bl + bw - dp(34), bt - dp(7), bl + bw - dp(14), bt + dp(2), dp(2), dp(2), p);

          // Metallic top plate & bottom baseplate chassis
          p.setShader(
              new LinearGradient(
                  bl, bt, bl + bw, bt + bh, metal, new float[] {0f, .52f, 1f}, Shader.TileMode.CLAMP));
          c.drawRoundRect(bl, bt, bl + bw, bt + bh, dp(18), dp(18), p);
          p.setShader(null);

          // Dark vulcanite leatherette middle grip wrap
          p.setColor(0xff171815);
          c.drawRect(bl, bt + dp(36), bl + bw, bt + bh - dp(14), p);

          // Viewfinder & rangefinder windows in top plate
          p.setColor(0xff161714);
          c.drawRoundRect(bl + dp(18), bt + dp(11), bl + dp(50), bt + dp(26), dp(3.5f), dp(3.5f), p);
          p.setColor(0xff2c362c);
          c.drawRoundRect(bl + dp(23), bt + dp(14.5f), bl + dp(45), bt + dp(22.5f), dp(2), dp(2), p);
          p.setColor(0xff262723);
          c.drawRoundRect(bl + dp(60), bt + dp(14), bl + dp(74), bt + dp(23), dp(2), dp(2), p);

          // Protruding multi-ring rangefinder lens
          float lx = cx + dp(6), ly = bt + dp(66);
          p.setColor(metal[1]);
          c.drawCircle(lx, ly, dp(36), p);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(dp(2));
          p.setColor(0xff111210);
          c.drawCircle(lx, ly, dp(36), p);

          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff141512);
          c.drawCircle(lx, ly, dp(29), p);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(dp(1.5f));
          p.setColor(0xff363831);
          c.drawCircle(lx, ly, dp(29), p);

          p.setStyle(Paint.Style.FILL);
          p.setColor(0xff0b0c0a);
          c.drawCircle(lx, ly, dp(18.5f), p);
          p.setStyle(Paint.Style.STROKE);
          p.setColor(0xff292b25);
          c.drawCircle(lx, ly, dp(18.5f), p);

          p.setStyle(Paint.Style.FILL);
          p.setColor(0x52e76736);
          c.drawCircle(lx - dp(7), ly - dp(7), dp(4.8f), p);
        }
      }
    };
  }

  /**
   * Minimalist, darkroom-toned 4-step introduction (Leica LUX / Halide inspired): generous dark
   * breathing space, literal photographic hardware illustrations, clean sans-serif typography, and
   * one primary CTA.
   */
  private void showOnboarding(int step) {
    stopMagnifier();
    onboardingStep = Math.max(0, Math.min(3, step));
    if (panel != null) root.removeView(panel);
    page = "onboarding";
    setDarkSystemBars(true);
    final int obBg = 0xff111210;
    final int obSurface = 0xff1a1b18;
    final int obLine = 0xff282a25;
    final int obInk = 0xfff3f0e6;
    final int obMuted = 0xff8e8c83;

    panel = column();
    panel.setBackgroundColor(obBg);
    panel.setClickable(true);
    panel.setPadding(dp(28), safeTop + dp(12), dp(28), safeBottom + dp(24));
    root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

    boolean alreadyDone = preferences.getBoolean("onboardingDone", false);
    boolean camGranted =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

    // Minimal top bar: wordmark + quiet Skip/Close text
    LinearLayout top = row();
    top.setGravity(Gravity.CENTER_VERTICAL);
    android.text.SpannableString mark = new android.text.SpannableString("grain.");
    mark.setSpan(
        new android.text.style.ForegroundColorSpan(ORANGE),
        5,
        6,
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    TextView brand = text("", 20, obInk);
    brand.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
    brand.setLetterSpacing(-.03f);
    brand.setText(mark);
    top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

    if (onboardingStep < 3 || alreadyDone) {
      TextView skip =
          button(
              alreadyDone ? "Close" : "Skip",
              13.5f,
              obMuted,
              () -> {
                if (alreadyDone) closeOnboarding();
                else showOnboarding(camGranted ? 3 : 2);
              });
      skip.setTypeface(Typeface.SANS_SERIF);
      skip.setPadding(dp(8), dp(6), dp(2), dp(6));
      skip.setMinHeight(0);
      top.addView(skip, new LinearLayout.LayoutParams(-2, -2));
    }
    panel.addView(top, new LinearLayout.LayoutParams(-1, dp(38)));

    // Center stage (weight = 1): literal photographic illustration + clean sans-serif pills
    LinearLayout stage = column();
    stage.setGravity(Gravity.CENTER);
    panel.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

    stage.addView(
        onboardingSculpture(onboardingStep, camGranted),
        new LinearLayout.LayoutParams(-1, dp(onboardingStep == 3 ? 168 : 206)));

    if (onboardingStep == 1) {
      String[] speeds = {"Auto", "1/1000", "1/500", "1/250"};
      LinearLayout strip = row();
      strip.setGravity(Gravity.CENTER);
      strip.setBackground(bg(obSurface, 999, obLine, 1));
      strip.setPadding(dp(3), dp(3), dp(3), dp(3));
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
      slp.topMargin = dp(12);
      for (int i = 0; i < speeds.length; i++) {
        final int idx = i;
        boolean sel = onboardingStock == idx;
        TextView pill =
            button(
                speeds[idx],
                12,
                sel ? obInk : obMuted,
                () -> {
                  onboardingStock = idx;
                  showOnboarding(1);
                });
        pill.setTypeface(Typeface.SANS_SERIF, sel ? Typeface.BOLD : Typeface.NORMAL);
        pill.setBackground(bg(sel ? 0xff2a2c27 : 0x00000000, 999, 0, 0));
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setMinHeight(0);
        strip.addView(pill, new LinearLayout.LayoutParams(-2, -2));
      }
      stage.addView(strip, slp);
    } else if (onboardingStep == 3) {
      LinearLayout seg = row();
      seg.setGravity(Gravity.CENTER);
      seg.setBackground(bg(obSurface, 999, obLine, 1));
      seg.setPadding(dp(4), dp(4), dp(4), dp(4));
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
      slp.topMargin = dp(16);
      String[] labels = {"Silver", "Black", "Graphite"};
      for (int i = 0; i < labels.length; i++) {
        final int idx = i;
        boolean sel = finish == idx;
        TextView opt =
            button(
                labels[idx],
                12.5f,
                sel ? obInk : obMuted,
                () -> {
                  finish = idx;
                  if (back != null) back.setFinish(finish);
                  savePrefs();
                  showOnboarding(3);
                });
        opt.setTypeface(Typeface.SANS_SERIF, sel ? Typeface.BOLD : Typeface.NORMAL);
        opt.setBackground(bg(sel ? 0xff2a2c27 : 0x00000000, 999, 0, 0));
        opt.setPadding(dp(16), dp(8), dp(16), dp(8));
        opt.setMinHeight(0);
        seg.addView(opt, new LinearLayout.LayoutParams(-2, -2));
      }
      stage.addView(seg, slp);
    }

    // Concise editorial copy block matching each icon 1-to-1
    String[] titles = {
      "Shoot like film.",
      "Real manual controls.",
      "Camera access, privately.",
      "Choose your camera body."
    };
    String[] subtitles = {
      "True sensor RAW and classic film stocks, without AI over-processing.",
      "Dial in shutter speed, ISO, flash, and focus just like a rangefinder.",
      "Used only to take photos. Nothing leaves your phone—no account, no cloud.",
      "Pick Silver, Black, or Graphite. You can switch anytime in Menu."
    };

    TextView title = text(titles[onboardingStep], 28.5f, obInk);
    title.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
    title.setLetterSpacing(-.025f);
    title.setLineSpacing(0, 1.08f);
    panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

    TextView sub = text(subtitles[onboardingStep], 14.5f, obMuted);
    sub.setTypeface(Typeface.SANS_SERIF);
    sub.setGravity(Gravity.START);
    sub.setSingleLine(false);
    sub.setLineSpacing(0, 1.38f);
    LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
    subLp.topMargin = dp(9);
    subLp.bottomMargin = dp(26);
    panel.addView(sub, subLp);

    // 4 minimal progress dashes
    LinearLayout dots = row();
    dots.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-1, -2);
    dotsLp.bottomMargin = dp(18);
    for (int i = 0; i < 4; i++) {
      boolean active = i == onboardingStep;
      View dot = new View(this);
      dot.setBackground(bg(active ? obInk : 0xff2c2e29, 4, 0, 0));
      LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(active ? 28 : 14), dp(3));
      if (i < 3) dlp.rightMargin = dp(6);
      dots.addView(dot, dlp);
    }
    panel.addView(dots, dotsLp);

    // Bottom action row: circular back button + clean 50dp pill CTA
    LinearLayout actions = row();
    actions.setGravity(Gravity.CENTER_VERTICAL);
    if (onboardingStep > 0) {
      TextView prev = button("‹", 20, obInk, () -> showOnboarding(onboardingStep - 1));
      prev.setTypeface(Typeface.SANS_SERIF);
      prev.setGravity(Gravity.CENTER);
      prev.setPadding(0, 0, 0, dp(2));
      prev.setMinHeight(0);
      prev.setBackground(bg(obSurface, 999, obLine, 1));
      LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(50), dp(50));
      plp.rightMargin = dp(10);
      actions.addView(prev, plp);
    }

    boolean isLast = onboardingStep == 3;
    boolean askPermNow = onboardingStep == 2 && !camGranted;
    String ctaLabel =
        isLast ? "Open Camera" : askPermNow ? "Allow Camera" : "Continue";
    TextView next =
        button(
            ctaLabel,
            15,
            isLast ? 0xffffffff : obBg,
            () -> {
              if (askPermNow) {
                permissionAsked = true;
                requestPermissions(new String[] {Manifest.permission.CAMERA}, 7);
              } else if (!isLast) {
                showOnboarding(onboardingStep + 1);
              } else {
                closeOnboarding();
              }
            });
    next.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
    next.setGravity(Gravity.CENTER);
    next.setBackground(bg(isLast ? ORANGE : obInk, 999, 0, 0));
    actions.addView(next, new LinearLayout.LayoutParams(0, dp(50), 1));
    panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));
  }

  private void showGallery() {
    if (busy) {
      message("Still saving your moment…");
      return;
    }
    java.util.List<PhotoStore.Photo> photos = store.list();
    if (photos.isEmpty()) {
      gallerySelectMode = false;
      gallerySelectedIds.clear();
    } else {
      java.util.Set<String> validIds = new java.util.HashSet<>();
      for (PhotoStore.Photo p : photos) validIds.add(p.id);
      gallerySelectedIds.retainAll(validIds);
    }

    openPanel(
        "The moments you kept.",
        photos.isEmpty()
            ? "NEWEST FIRST · PRIVATE ON THIS DEVICE"
            : photos.size()
                + (photos.size() == 1 ? " PHOTO" : " PHOTOS")
                + " · PRIVATE ON THIS DEVICE");

    // Grab the header row created by openPanel() so we can add Select / All / Done controls cleanly
    LinearLayout head = (LinearLayout) panel.getChildAt(0);
    LinearLayout titles = (LinearLayout) head.getChildAt(0);
    TextView heading = (TextView) titles.getChildAt(0);
    TextView sub = (TextView) titles.getChildAt(1);
    View closeBtn = head.getChildAt(1);

    LinearLayout body = column();
    body.setPadding(dp(17), dp(12), dp(17), dp(20));
    panelScroll.addView(body);

    if (photos.isEmpty()) {
      TextView art = iconButton("camera", 0xff82926f, 31, () -> {});
      art.setClickable(false);
      art.setFocusable(false);
      art.setBackground(bg(0xffe7ebde, 0, 0xffcfd7c1, 1));
      art.setPadding(dp(26), 0, 0, 0);
      art.setRotation(-7);
      LinearLayout.LayoutParams artLp = size(84, 100);
      artLp.gravity = Gravity.CENTER;
      artLp.topMargin = dp(68);
      artLp.bottomMargin = dp(27);
      body.addView(art, artLp);
      TextView empty = text("Your first frame is waiting.", 27, INK);
      empty.setTypeface(Typeface.SERIF);
      body.addView(empty, new LinearLayout.LayoutParams(-1, dp(43)));
      body.addView(paragraph("No sample photos pretending to be yours.\nGo make a little memory."));
      TextView shoot = button("Take your first photo ↗", 11, PAPER, this::closePanel);
      shoot.setBackground(bg(INK, 25, 0, 0));
      LinearLayout.LayoutParams shootLp = size(198, 42);
      shootLp.gravity = Gravity.CENTER;
      shootLp.topMargin = dp(12);
      shootLp.bottomMargin = dp(30);
      body.addView(shoot, shootLp);
      body.addView(
          paragraph(
              "Stored privately. Export photos you want in the system gallery. Uninstalling Grain"
                  + " deletes its private photos."));
      return;
    }

    // Header selection controls (inserted right before the ✕ button)
    TextView selectBtn = button("Select", 11, INK, () -> {});
    selectBtn.setBackground(bg(0xffebe8dd, 999, LINE, 1));
    selectBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
    selectBtn.setMinHeight(0);
    LinearLayout.LayoutParams selBtnLp = new LinearLayout.LayoutParams(-2, -2);
    selBtnLp.gravity = Gravity.CENTER_VERTICAL;
    selBtnLp.rightMargin = dp(8);
    head.addView(selectBtn, 1, selBtnLp);

    TextView selectAllBtn = button("All", 11, INK, () -> {});
    selectAllBtn.setBackground(bg(0xffebe8dd, 999, LINE, 1));
    selectAllBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
    selectAllBtn.setMinHeight(0);
    LinearLayout.LayoutParams allBtnLp = new LinearLayout.LayoutParams(-2, -2);
    allBtnLp.gravity = Gravity.CENTER_VERTICAL;
    allBtnLp.rightMargin = dp(6);
    head.addView(selectAllBtn, 2, allBtnLp);

    TextView doneSelBtn = button("Done", 11, PAPER, () -> {});
    doneSelBtn.setBackground(bg(INK, 999, 0, 0));
    doneSelBtn.setPadding(dp(13), dp(6), dp(13), dp(6));
    doneSelBtn.setMinHeight(0);
    LinearLayout.LayoutParams doneBtnLp = new LinearLayout.LayoutParams(-2, -2);
    doneBtnLp.gravity = Gravity.CENTER_VERTICAL;
    doneBtnLp.rightMargin = dp(6);
    head.addView(doneSelBtn, 3, doneBtnLp);

    // Sticky bottom action bar for multi-select Save & Delete
    LinearLayout actionWrap = column();
    View barLine = new View(this);
    barLine.setBackgroundColor(LINE);
    actionWrap.addView(barLine, new LinearLayout.LayoutParams(-1, dp(1)));
    LinearLayout actionRow = row();
    actionRow.setPadding(dp(17), dp(10), dp(17), dp(14));
    actionRow.setBackgroundColor(PAPER);

    TextView batchSaveBtn = button("Save", 12, INK, () -> {});
    batchSaveBtn.setBackground(bg(0xffebe8dd, 10, LINE, 1));
    LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(44), 1);
    saveLp.rightMargin = dp(8);
    actionRow.addView(batchSaveBtn, saveLp);

    TextView batchDelBtn = button("Delete", 12, PAPER, () -> {});
    batchDelBtn.setBackground(bg(ORANGE, 10, 0, 0));
    actionRow.addView(batchDelBtn, new LinearLayout.LayoutParams(0, dp(44), 1));
    actionWrap.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));
    panel.addView(actionWrap, new LinearLayout.LayoutParams(-1, -2));

    final List<LinearLayout> cardViews = new ArrayList<>(photos.size());
    final List<TextView> badgeViews = new ArrayList<>(photos.size());

    final Runnable syncSelectionUi =
        () -> {
          int n = gallerySelectedIds.size();
          int total = photos.size();
          if (gallerySelectMode) {
            heading.setText(n > 0 ? n + " selected" : "Select photos");
            sub.setText(
                total
                    + (total == 1 ? " PHOTO" : " PHOTOS")
                    + " · TAP TO TOGGLE");
            selectBtn.setVisibility(View.GONE);
            selectAllBtn.setVisibility(View.VISIBLE);
            doneSelBtn.setVisibility(View.VISIBLE);
            closeBtn.setVisibility(View.GONE);
            selectAllBtn.setText(n == total ? "None" : "All");
            actionWrap.setVisibility(View.VISIBLE);
            batchSaveBtn.setEnabled(n > 0);
            batchSaveBtn.setAlpha(n > 0 ? 1f : .42f);
            batchSaveBtn.setText(n > 0 ? "Save (" + n + ")" : "Save");
            batchDelBtn.setEnabled(n > 0);
            batchDelBtn.setAlpha(n > 0 ? 1f : .42f);
            batchDelBtn.setText(n > 0 ? "Delete (" + n + ")" : "Delete");
          } else {
            heading.setText("The moments you kept.");
            sub.setText(
                total
                    + (total == 1 ? " PHOTO" : " PHOTOS")
                    + " · PRIVATE ON THIS DEVICE");
            selectBtn.setVisibility(View.VISIBLE);
            selectAllBtn.setVisibility(View.GONE);
            doneSelBtn.setVisibility(View.GONE);
            closeBtn.setVisibility(View.VISIBLE);
            actionWrap.setVisibility(View.GONE);
          }
          for (int k = 0; k < photos.size(); k++) {
            boolean sel = gallerySelectMode && gallerySelectedIds.contains(photos.get(k).id);
            LinearLayout c = cardViews.get(k);
            TextView b = badgeViews.get(k);
            c.setBackground(bg(0xfffffcf4, 6, sel ? ORANGE : LINE, sel ? 2 : 1));
            if (gallerySelectMode) {
              b.setVisibility(View.VISIBLE);
              b.setText(sel ? "✓" : "");
              b.setBackground(
                  bg(sel ? ORANGE : 0x70111210, 999, 0xe6ffffff, sel ? 1 : 2));
            } else {
              b.setVisibility(View.GONE);
            }
          }
        };

    selectBtn.setOnClickListener(
        v -> {
          gallerySelectMode = true;
          syncSelectionUi.run();
        });
    doneSelBtn.setOnClickListener(
        v -> {
          gallerySelectMode = false;
          gallerySelectedIds.clear();
          syncSelectionUi.run();
        });
    selectAllBtn.setOnClickListener(
        v -> {
          if (gallerySelectedIds.size() == photos.size()) {
            gallerySelectedIds.clear();
          } else {
            for (PhotoStore.Photo p : photos) gallerySelectedIds.add(p.id);
          }
          syncSelectionUi.run();
        });
    batchDelBtn.setOnClickListener(
        v -> {
          List<PhotoStore.Photo> chosen = new ArrayList<>();
          for (PhotoStore.Photo p : photos) {
            if (gallerySelectedIds.contains(p.id)) chosen.add(p);
          }
          if (!chosen.isEmpty()) deleteBatch(chosen);
        });
    batchSaveBtn.setOnClickListener(
        v -> {
          List<PhotoStore.Photo> chosen = new ArrayList<>();
          for (PhotoStore.Photo p : photos) {
            if (gallerySelectedIds.contains(p.id)) chosen.add(p);
          }
          if (!chosen.isEmpty()) exportBatch(chosen);
        });

    for (int i = 0; i < photos.size(); i += 2) {
      LinearLayout row = row();
      row.setGravity(Gravity.TOP);
      body.addView(row);
      for (int j = i; j < Math.min(i + 2, photos.size()); j++) {
        PhotoStore.Photo photo = photos.get(j);
        LinearLayout card = column();
        card.setPadding(dp(5), dp(5), dp(5), dp(7));
        card.setBackground(bg(0xfffffcf4, 6, LINE, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(card, cp);

        FrameLayout thumbBox = new FrameLayout(this);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription("Open captured photo");
        thumbBox.addView(image, new FrameLayout.LayoutParams(-1, dp(180)));
        loadImage(image, photo.image, 400);

        TextView badge = text("", 11, 0xffffffff);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.TOP | Gravity.END);
        blp.setMargins(dp(7), dp(7), dp(7), dp(7));
        thumbBox.addView(badge, blp);
        card.addView(thumbBox, new LinearLayout.LayoutParams(-1, dp(180)));

        JSONObject r = photo.metadata.optJSONObject("recipe");
        card.addView(
            text(
                (r == null ? "Photo" : r.optString("name"))
                    + " · "
                    + photo.metadata.optString("ratio"),
                10,
                MUTED),
            new LinearLayout.LayoutParams(-1, dp(29)));

        card.setOnClickListener(
            v -> {
              if (gallerySelectMode) {
                if (!gallerySelectedIds.remove(photo.id)) gallerySelectedIds.add(photo.id);
                syncSelectionUi.run();
              } else {
                showPhoto(photo);
              }
            });
        card.setOnLongClickListener(
            v -> {
              v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
              gallerySelectMode = true;
              gallerySelectedIds.add(photo.id);
              syncSelectionUi.run();
              return true;
            });

        cardViews.add(card);
        badgeViews.add(badge);
      }
      if (i + 1 == photos.size())
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    body.addView(
        paragraph(
            "Tip: Long-press any photo (or tap Select) to choose multiple frames at once."
                + " Stored privately — uninstalling Grain deletes its private photos."));
    syncSelectionUi.run();
  }

  private void showPhoto(PhotoStore.Photo photo) {
    openPanel(
        "A moment, kept.",
        photo.metadata.optString("ratio")
            + " · "
            + photo.metadata.optInt("width")
            + " × "
            + photo.metadata.optInt("height"));
    page = "detail";
    LinearLayout body = column();
    body.setPadding(dp(20), dp(8), dp(20), dp(20));
    panelScroll.addView(body);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setAdjustViewBounds(true);
    body.addView(image, new LinearLayout.LayoutParams(-1, dp(420)));
    loadImage(image, photo.image, 1400);
    JSONObject r = photo.metadata.optJSONObject("recipe");
    TextView name = text(r == null ? "Photo" : r.optString("name"), 27, INK);
    name.setTypeface(Typeface.SERIF);
    body.addView(name);
    body.addView(paragraph("FILE · " + PhotoStore.exportFileName(photo, false)));
    body.addView(
        paragraph(
            String.format(
                Locale.US,
                photo.raw() ? "%.1f× DIGITAL CROP · DEVELOPED FROM RAW · %s" : "%.1f× DIGITAL CROP · JPEG%s",
                photo.metadata.optDouble("digitalZoom", 1),
                photo.raw() && r != null ? lutLabel(r.optString("filmSimulationLut", "")) : "")));
    TextView save =
        button("Save to device gallery ↓", 13, PAPER, () -> export(photo, false, false));
    save.setBackground(bg(INK, 8, 0, 0));
    GrainStyle.leading(save, "download", PAPER, 15);
    save.setPadding(dp(18), 0, dp(12), 0);
    body.addView(save, new LinearLayout.LayoutParams(-1, dp(47)));
    body.addView(button("Share photo", 12, () -> export(photo, false, true)));
    if (photo.original != null) {
      body.addView(button("Save full-frame recipe capture", 12, () -> export(photo, true, false)));
      body.addView(
          paragraph(
              photo.raw()
                  ? "Full-frame means no framing crop or digital zoom; the recipe is applied."
                      + " The untouched sensor data is the DNG below."
                  : "Full-frame means no framing crop or digital zoom. The camera’s recipe is"
                      + " already baked in. This is not an unfiltered original."));
    }
    if (photo.dng != null || photo.phoneJpeg != null) {
      TextView test = button("Save RAW test files (DNG · phone JPEG · receipt)", 12, () -> exportTestFiles(photo));
      body.addView(test);
      body.addView(
          paragraph(
              "For tuning: the DNG and phone JPEG go to Pictures/Grain, the receipt to"
                  + " Download/Grain. Send them along with this photo."));
    }
    if (photo.metadata.optBoolean("recoveredFullFrame"))
      body.addView(
          paragraph(
              "The requested crop failed. This is the recovered full-frame capture, not the"
                  + " requested framing. See the receipt for details."));
    body.addView(
        button("View capture receipt", 12, () -> showJson("Capture receipt", photo.metadata)));
    body.addView(button("Delete photo", 11, ORANGE, () -> delete(photo)));
    body.addView(button("‹ Back to photos", 12, this::showGallery));
  }

  private void delete(PhotoStore.Photo photo) {
    deleteBatch(java.util.Collections.singletonList(photo));
  }

  private void deleteBatch(List<PhotoStore.Photo> targets) {
    if (targets == null || targets.isEmpty()) return;
    if (purgeTask != null) main.removeCallbacks(purgeTask);
    pendingDelete.clear();
    store.purgeTrash();
    for (PhotoStore.Photo p : targets) {
      if (store.trash(p)) pendingDelete.add(p);
    }
    if (pendingDelete.isEmpty()) {
      message("Could not delete selected photo(s)");
      return;
    }
    int count = pendingDelete.size();
    gallerySelectMode = false;
    gallerySelectedIds.clear();
    showGallery();
    updateThumb();
    showSnackbar(
        count == 1 ? "Photo deleted." : count + " photos deleted.",
        "Undo",
        () -> {
          int restored = 0;
          for (PhotoStore.Photo p : pendingDelete) {
            if (store.restore(p)) restored++;
          }
          pendingDelete.clear();
          if (purgeTask != null) main.removeCallbacks(purgeTask);
          showGallery();
          updateThumb();
          if (restored > 0) {
            message(restored == 1 ? "Photo restored." : restored + " photos restored.");
          }
        });
    purgeTask =
        () -> {
          pendingDelete.clear();
          store.purgeTrash();
        };
    main.postDelayed(purgeTask, 7000);
  }

  private void exportBatch(List<PhotoStore.Photo> targets) {
    if (targets == null || targets.isEmpty()) return;
    int total = targets.size();
    gallerySelectMode = false;
    gallerySelectedIds.clear();
    showGallery();
    message(total == 1 ? "Saving photo…" : "Saving " + total + " photos…");
    files.execute(
        () -> {
          int saved = 0;
          for (PhotoStore.Photo p : targets) {
            try {
              store.export(p, false);
              saved++;
            } catch (Exception ignored) {
            }
          }
          final int done = saved;
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                if (done > 0) {
                  message(
                      done == 1
                          ? "Saved to device gallery."
                          : "Saved " + done + " photos to device gallery.");
                } else {
                  message("Could not export selected photos.");
                }
              });
        });
  }

  private void export(PhotoStore.Photo photo, boolean full, boolean share) {
    message("Preparing export…");
    files.execute(
        () -> {
          try {
            Uri uri = store.export(photo, full);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  if (share) {
                    Intent send =
                        new Intent(Intent.ACTION_SEND)
                            .setType("image/jpeg")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share this moment"));
                  } else message("Saved to Pictures/Grain");
                });
          } catch (Exception e) {
            runOnUiThread(() -> message("Export failed: " + e.getMessage()));
          }
        });
  }

  private void showDiagnostics() {
    try {
      String summary = halSummary();
      JSONObject j = new JSONObject();
      // The high-resolution paths go first: they are what a long report gets cut off before.
      j.put("hal", capabilities.opt("hal"));
      j.put("halSummary", summary);
      for (Iterator<String> it = capabilities.keys(); it.hasNext(); ) {
        String key = it.next();
        if ("hal".equals(key)) continue;
        j.put(key, capabilities.opt(key));
      }
      String crash = CrashLog.lastCrash(this);
      j.put("lastCrash", crash == null || crash.isEmpty() ? "none" : crash);
      showJson("Camera diagnostics", j, summary);
    } catch (Exception e) {
      showJson("Camera diagnostics", capabilities);
    }
  }

  /**
   * One short report of the numbers that decide how large a photo this phone can take: the biggest
   * public output size, and anything the vendor says about a larger one. Deliberately short, so it
   * survives any clipboard, and it is the part that matters.
   */
  private String halSummary() {
    JSONObject hal = capabilities.optJSONObject("hal");
    if (hal == null) return "";
    StringBuilder s = new StringBuilder();
    s.append("Grain camera HAL summary\n");
    s.append("device ")
        .append(capabilities.optString("device"))
        .append(" · sdk ")
        .append(capabilities.optInt("sdk"))
        .append(" · hardware level ")
        .append(capabilities.optInt("hardwareLevel"));
    JSONObject sizes = hal.optJSONObject("streamSizes");
    if (sizes != null) {
      s.append("\npublic sizes: jpeg ")
          .append(largestSize(sizes.optJSONArray("jpeg")))
          .append(" · raw ")
          .append(largestSize(sizes.optJSONArray("raw")))
          .append(" · yuv ")
          .append(largestSize(sizes.optJSONArray("yuv")));
    }
    JSONObject max = hal.optJSONObject("maximumResolutionSizes");
    if (max != null) {
      s.append("\nmaximum-resolution map: jpeg ")
          .append(largestSize(max.optJSONArray("jpeg")))
          .append(" · raw ")
          .append(largestSize(max.optJSONArray("raw")))
          .append(" · yuv ")
          .append(largestSize(max.optJSONArray("yuv")));
    }
    JSONArray requestKeys = hal.optJSONArray("vendorRequestKeys");
    if (requestKeys != null) {
      StringBuilder keys = new StringBuilder();
      for (int i = 0; i < requestKeys.length(); i++) {
        String key = requestKeys.optString(i, "");
        if (!key.matches("(?i).*(superres|remosaic).*")) continue;
        if (keys.length() > 0) keys.append(", ");
        keys.append(key);
      }
      if (keys.length() > 0) s.append("\nvendor switches: ").append(keys);
    }
    JSONObject vendor = hal.optJSONObject("vendorCharacteristics");
    if (vendor != null) {
      for (Iterator<String> it = vendor.keys(); it.hasNext(); ) {
        String key = it.next();
        if (!key.matches("(?i).*(superres|remosaic|rawsize|sat\\.|scaler).*")) continue;
        Object value = vendor.opt(key);
        if (value == null || value == JSONObject.NULL) continue;
        s.append("\n").append(key).append(" = ").append(summarise(value));
      }
    }
    return s.toString();
  }

  /** The largest of a list of "width x height" strings, with its megapixels. */
  private static String largestSize(JSONArray sizes) {
    if (sizes == null) return "none";
    long best = 0;
    String bestText = "none";
    for (int i = 0; i < sizes.length(); i++) {
      String entry = sizes.optString(i, "");
      int x = entry.indexOf('x');
      if (x <= 0) continue;
      long px;
      try {
        px = Long.parseLong(entry.substring(0, x).trim()) * Long.parseLong(entry.substring(x + 1).trim());
      } catch (NumberFormatException e) {
        continue;
      }
      if (px > best) {
        best = px;
        bestText =
            entry + " (" + String.format(Locale.US, "%.1f MP", px / 1_000_000.0) + ")";
      }
    }
    return bestText;
  }

  /** A vendor characteristic in one line: a stream list becomes "WxH fmt F", anything else prints. */
  private static String summarise(Object value) {
    if (!(value instanceof JSONArray)) return String.valueOf(value);
    JSONArray array = (JSONArray) value;
    if (array.length() == 0) return "empty";
    if (array.length() % 4 == 0 && allIntegers(array)) {
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < array.length(); i += 4) {
        if (i > 0) s.append(", ");
        s.append(array.optInt(i + 1)).append("x").append(array.optInt(i + 2)).append(" fmt ").append(array.optInt(i));
      }
      return s.toString();
    }
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < array.length(); i++) {
      if (i > 0) s.append(", ");
      s.append(array.opt(i));
    }
    return s.toString();
  }

  private static boolean allIntegers(JSONArray array) {
    for (int i = 0; i < array.length(); i++) {
      Object v = array.opt(i);
      if (!(v instanceof Integer) && !(v instanceof Long)) return false;
    }
    return true;
  }

  private void exportTestFiles(PhotoStore.Photo photo) {
    message("Saving test files…");
    files.execute(
        () -> {
          try {
            List<String> done = store.exportTestFiles(photo);
            runOnUiThread(() -> message("Saved " + String.join(", ", done) + " · Pictures/Grain + Download/Grain"));
          } catch (Exception e) {
            runOnUiThread(() -> message("Could not save test files: " + e.getMessage()));
          }
        });
  }

  private void showJson(String title, JSONObject json) {
    showJson(title, json, null);
  }

  /**
   * Shows a JSON document. When a summary is given it gets its own button, because some clipboards
   * will not take a very long string and the summary is the part that matters.
   */
  private void showJson(String title, JSONObject json, String summary) {
    LinearLayout box = column();
    ScrollView scroll = new ScrollView(this);
    TextView text = text(json.toString(), 10, INK);
    try {
      text.setText(json.toString(2).replace("\\/", "/"));
    } catch (Exception ignored) {
    }
    text.setTypeface(Typeface.SANS_SERIF);
    text.setGravity(Gravity.TOP | Gravity.START);
    text.setPadding(dp(16), dp(12), dp(16), dp(12));
    text.setTextIsSelectable(true);
    scroll.addView(text);
    box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    if (summary != null && !summary.isEmpty()) {
      LinearLayout row = row();
      row.setPadding(dp(8), dp(2), dp(8), dp(8));
      row.addView(button("Copy summary", 11, () -> copyText(summary, "Summary copied")));
      row.addView(button("Save as .txt", 11, () -> saveReport(title, json)));
      box.addView(row);
    }
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("Close", null)
        .setNeutralButton("Copy", (d, w) -> copyText(plainJson(json), title + " copied"))
        .show();
  }

  private static String plainJson(JSONObject json) {
    try {
      return json.toString(2).replace("\\/", "/");
    } catch (Exception e) {
      return json.toString();
    }
  }

  private void copyText(String value, String message) {
    android.content.ClipboardManager clip =
        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    clip.setPrimaryClip(ClipData.newPlainText("Grain", value));
    message(message);
  }

  /** Saves the report as a text file in Download/Grain: a long report can be cut off by the clipboard. */
  private void saveReport(String title, JSONObject json) {
    files.execute(
        () -> {
          try {
            String name =
                "grain-" + title.toLowerCase(Locale.ROOT).replace(' ', '-') + ".txt";
            store.writeText(name, plainJson(json));
            runOnUiThread(() -> message("Saved to Download/Grain · " + name));
          } catch (Exception e) {
            runOnUiThread(() -> message("Could not save the report: " + e.getMessage()));
          }
        });
  }

  private void loadImage(ImageView image, File file, int max) {
    files.execute(
        () -> {
          Bitmap bitmap = store.thumbnail(file, max);
          runOnUiThread(
              () -> {
                if (!isDestroyed() && image.isAttachedToWindow()) image.setImageBitmap(bitmap);
                else if (bitmap != null) bitmap.recycle();
              });
        });
  }

  private void loadPrefs() {
    recipe = Math.max(0, Math.min(Recipe.ALL.length - 1, preferences.getInt("recipe", 0)));
    for (int i = 0; i < strengths.length; i++)
      strengths[i] = Math.max(0, Math.min(100, preferences.getInt("strength" + i, 80)));
    keepRawFiles = preferences.getBoolean("keepRawFiles", true);
    ratio = Framing.ratio(preferences.getString("ratio", "3:4"));
    gridStyle =
        Math.max(
            0, Math.min(GRID_STYLES.length - 1, preferences.getInt("gridStyle", preferences.getBoolean("grid", false) ? 1 : 0)));
    keepFull = preferences.getBoolean("keepFull", true);
    larger = preferences.getBoolean("larger", false);
    faceExposure = preferences.getBoolean("faceExposure", true);
    stackOff = preferences.getBoolean("stackOff", false);
    faceLandmarks = preferences.getBoolean("faceLandmarks", false);
    afArea = Math.max(0, Math.min(4, preferences.getInt("afArea", GrainCamera.AF_SINGLE)));
    afLock = preferences.getBoolean("afLock", false);
    mfOn = preferences.getBoolean("mfOn", false);
    focusMode =
        preferences.contains("focusMode")
            ? Math.max(0, Math.min(2, preferences.getInt("focusMode", FocusMath.FOCUS_AFC)))
            : (mfOn ? FocusMath.FOCUS_MANUAL : FocusMath.FOCUS_AFC);
    mfOn = focusMode == FocusMath.FOCUS_MANUAL;
    mfStep = Math.max(0, Math.min(MF_STEPS, preferences.getInt("mfStep", MF_STEPS / 2)));
    peaking = preferences.getBoolean("peaking", false);
    magnifier = Math.max(0, Math.min(3, preferences.getInt("magnifier", FocusMath.MAG_4X_HOLD)));
    bktFramesIndex = Math.max(0, Math.min(2, preferences.getInt("bktFramesIndex", 1)));
    bktStepMode = Math.max(0, Math.min(2, preferences.getInt("bktStepMode", FocusMath.BKT_STEP_MEDIUM)));
    notifyUpdates = preferences.getBoolean("notifyUpdates", true);
    skippedUpdateTag = preferences.getString("skippedUpdateTag", "");
    timerSeconds = preferences.getInt("timer", 0);
    if (timerSeconds != 3 && timerSeconds != 5) timerSeconds = 0;
    drive =
        preferences.getInt(
            "drive",
            timerSeconds == 3
                ? DRIVE_TIMER_3
                : timerSeconds == 5 ? DRIVE_TIMER_5 : DRIVE_SINGLE);
    if (drive < 0 || drive >= DRIVES.length) drive = DRIVE_SINGLE;
    timerSeconds = driveTimerSeconds(drive);
    dispMode = Math.max(0, Math.min(1, preferences.getInt("disp", 0)));
    viewMode = Math.max(0, Math.min(2, preferences.getInt("viewMode", 0)));
    finish = Math.max(0, Math.min(2, preferences.getInt("finish", 0)));
    sounds = preferences.getBoolean("sounds", true);
    showHistogram = preferences.getBoolean("histogram", true);
    showLevel = preferences.getBoolean("level", true);
    remoteOn = preferences.getBoolean("remoteOn", false);
    remoteToken = preferences.getString("remoteToken", "");
    autoRotate = preferences.getBoolean("autoRotate", true);
    isoDial = preferences.getBoolean("isoDial", false);
    shutterNs = Math.max(0L, preferences.getLong("shutterNs", 0L));
    isoSetting = Math.max(0, preferences.getInt("isoSetting", 0));
    flashMode =
        Math.max(
            ExposureMath.FLASH_OFF,
            Math.min(ExposureMath.FLASH_TORCH, preferences.getInt("flashMode", 0)));
  }

  private void savePrefs() {
    if (preferences == null) return;
    SharedPreferences.Editor edit =
        preferences
            .edit()
            .putInt("recipe", recipe)
            .putString("ratio", ratio)
            .putInt("gridStyle", gridStyle)
            .remove("grid")
            .putBoolean("keepFull", keepFull)
            .putBoolean("larger", larger)
            .putBoolean("faceExposure", faceExposure)
            .putBoolean("stackOff", stackOff)
            .putBoolean("faceLandmarks", faceLandmarks)
            .putInt("afArea", afArea)
            .putBoolean("afLock", afLock)
            .putInt("focusMode", focusMode)
            .putBoolean("mfOn", mfOn)
            .putInt("mfStep", mfStep)
            .putBoolean("peaking", peaking)
            .putInt("magnifier", magnifier)
            .putInt("bktFramesIndex", bktFramesIndex)
            .putInt("bktStepMode", bktStepMode)
            .putBoolean("notifyUpdates", notifyUpdates)
            .putString("skippedUpdateTag", skippedUpdateTag)
            .putBoolean("keepRawFiles", keepRawFiles)
            .putInt("timer", timerSeconds)
            .putInt("drive", drive)
            .putInt("disp", dispMode)
            .putInt("viewMode", viewMode)
            .putInt("finish", finish)
            .putBoolean("sounds", sounds)
            .putBoolean("histogram", showHistogram)
            .putBoolean("level", showLevel)
            .putBoolean("remoteOn", remoteOn)
            .putString("remoteToken", remoteToken)
            .putBoolean("autoRotate", autoRotate)
            .putBoolean("isoDial", isoDial)
            .putLong("shutterNs", shutterNs)
            .putInt("isoSetting", isoSetting)
            .putInt("flashMode", flashMode);
    for (int i = 0; i < strengths.length; i++) edit.putInt("strength" + i, strengths[i]);
    edit.apply();
  }

  private void message(String text) {
    if (!isDestroyed()) showSnackbar(text, null, null);
  }

  private void showSnackbar(String text, String action, Runnable onAction) {
    if (snackbar != null && snackbar.getParent() instanceof ViewGroup)
      ((ViewGroup) snackbar.getParent()).removeView(snackbar);
    ViewGroup host = panel != null || back == null ? root : ui;
    if (host == null) {
      Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
      return;
    }
    LinearLayout bar = row();
    bar.setPadding(dp(14), dp(9), dp(12), dp(9));
    bar.setElevation(dp(12));
    bar.setBackground(bg(INK, 12, 0, 0));
    TextView label = text(text, 11, PAPER);
    label.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    if (action != null) {
      TextView button =
          button(
              action,
              12,
              0xffffce9d,
              () -> {
                host.removeView(bar);
                onAction.run();
              });
      bar.addView(button, size(58, 40));
    }
    FrameLayout.LayoutParams lp;
    if (host == ui) {
      RectF screen = back.screenRect();
      lp = new FrameLayout.LayoutParams(Math.max(dp(160), Math.round(screen.width()) - dp(20)), -2, Gravity.TOP | Gravity.START);
      lp.leftMargin = Math.round(screen.left) + dp(10);
      lp.topMargin = Math.round(screen.top) + dp(10);
    } else {
      lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
      lp.setMargins(dp(15), 0, dp(15), dp(18) + safeBottom);
    }
    host.addView(bar, lp);
    snackbar = bar;
    main.postDelayed(
        () -> {
          host.removeView(bar);
          if (snackbar == bar) snackbar = null;
        },
        action == null ? 4200 : 6500);
  }

  private TextView iconButton(String name, int color, int pixels, Runnable action) {
    TextView view = button("", 12, color, action);
    GrainStyle.icon(view, name, color, pixels);
    view.setContentDescription(name);
    return view;
  }

  private SeekBar styledSlider(int max, int progress) {
    SeekBar bar = new SeekBar(this);
    bar.setMax(max);
    bar.setProgress(progress);
    bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ORANGE));
    bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ORANGE));
    bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffb9c0af));
    bar.setPadding(dp(2), 0, dp(2), 0);
    return bar;
  }

  private void dismissCameraTool() {
    if (!"camera".equals(mode)) closeOverlays();
  }

  private void sheetHeader(LinearLayout body, String title, String subtitle, Runnable onClose) {
    LinearLayout head = row();
    head.setPadding(dp(23), 0, dp(23), dp(12));
    LinearLayout labels = column();
    TextView h = text(title, 26, INK);
    h.setTypeface(Typeface.SERIF);
    h.setGravity(Gravity.START);
    labels.addView(h);
    TextView sub = text(subtitle, 8, MUTED);
    sub.setLetterSpacing(.1f);
    sub.setGravity(Gravity.START);
    sub.setPadding(0, dp(6), 0, 0);
    labels.addView(sub);
    head.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    TextView close = iconButton("close", INK, 13, onClose);
    close.setMinHeight(0);
    close.setPadding(dp(8), 0, 0, 0);
    close.setBackground(bg(PAPER, 30, LINE, 1));
    close.setContentDescription("Close film lab");
    head.addView(close, size(30, 30));
    body.addView(head);
  }

  private void styleFilmTile(LinearLayout tile, boolean selected) {
    tile.setBackground(
        bg(selected ? 0xffe7ecdd : PAPER, 8, selected ? 0xff4f653d : LINE, selected ? 2 : 1));
    tile.setSelected(selected);
    View mark = tile.findViewWithTag("selected-check");
    if (mark != null) mark.setVisibility(selected ? View.VISIBLE : View.GONE);
  }

  private void sectionLabel(LinearLayout body, String title) {
    TextView label = text(title, 9, MUTED);
    label.setLetterSpacing(.15f);
    label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(31));
    lp.topMargin = dp(12);
    body.addView(label, lp);
  }

  private void preferenceToggle(
      LinearLayout body,
      String title,
      String description,
      boolean checked,
      java.util.function.Consumer<Boolean> action) {
    LinearLayout line = row();
    line.setPadding(0, dp(13), 0, dp(13));
    LinearLayout labels = column();
    TextView name = text(title, 12, INK);
    name.setGravity(Gravity.START);
    labels.addView(name);
    TextView desc = text(description, 10, MUTED);
    desc.setGravity(Gravity.START);
    desc.setLineSpacing(dp(3), 1);
    desc.setPadding(0, dp(6), dp(12), 0);
    labels.addView(desc);
    line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    Switch toggle = new Switch(this);
    toggle.setChecked(checked);
    toggle.setContentDescription(title);
    toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(PAPER));
    toggle.setTrackTintList(GrainStyle.tint(0xff596b43, 0xffc9cdbd));
    toggle.setOnCheckedChangeListener((v, value) -> action.accept(value));
    line.addView(toggle);
    body.addView(line);
    View divider = new View(this);
    divider.setBackgroundColor(LINE);
    body.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  private void preferenceInfo(LinearLayout body, String title, String description, String value) {
    LinearLayout line = row();
    line.setPadding(0, dp(14), 0, dp(14));
    LinearLayout labels = column();
    TextView name = text(title, 12, INK);
    name.setGravity(Gravity.START);
    labels.addView(name);
    TextView desc = text(description, 10, MUTED);
    desc.setGravity(Gravity.START);
    desc.setPadding(0, dp(6), dp(10), 0);
    labels.addView(desc);
    line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    TextView v = text(value, 9, 0xff7f8e6e);
    v.setGravity(Gravity.END);
    line.addView(v, size(92, -2));
    body.addView(line);
    View divider = new View(this);
    divider.setBackgroundColor(LINE);
    body.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  private LinearLayout dialogBody() {
    LinearLayout b = column();
    b.setPadding(dp(23), dp(18), dp(23), dp(17));
    return b;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView text(String value, float sp, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setGravity(Gravity.CENTER);
    t.setIncludeFontPadding(false);
    return t;
  }

  private TextView paragraph(String value) {
    TextView t = text(value, 11, MUTED);
    t.setGravity(Gravity.START);
    t.setLineSpacing(dp(4), 1);
    t.setPadding(0, dp(12), 0, dp(13));
    return t;
  }

  private TextView button(String value, float sp, Runnable click) {
    return button(value, sp, INK, click);
  }

  private TextView button(String value, float sp, int color, Runnable click) {
    TextView t = text(value, sp, color);
    t.setMinHeight(dp(40));
    t.setClickable(true);
    t.setFocusable(true);
    t.setOnClickListener(v -> click.run());
    return t;
  }

  private GradientDrawable bg(int color, int radius, int stroke, int width) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (width > 0) d.setStroke(dp(width), stroke);
    return d;
  }

  private LinearLayout.LayoutParams size(int w, int h) {
    return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private interface IntValue {
    void set(int value);
  }

  private SeekBar.OnSeekBarChangeListener seek(IntValue value) {
    return new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar s, int p, boolean user) {
        if (user) value.set(p);
      }

      @Override
      public void onStartTrackingTouch(SeekBar s) {}

      @Override
      public void onStopTrackingTouch(SeekBar s) {}
    };
  }

  // ───────────────────────────── the camera back ─────────────────────────────

  private final int[] histogramBins = new int[64];
  private Bitmap peakBitmap;
  private int[] peakPixels, peakLuma, peakOut;
  /** Consecutive failed preview read-backs; peaking gives up after {@link #PEAK_MAX_FAILURES}. */
  private int peakFailures;
  /** True when the last {@link #readPreview} failed rather than simply having nothing to read. */
  private boolean lastReadFailed;
  /** The recorded crash report is shown once, on the first resume after a crash. */
  private boolean crashShown;
  /** The preview read-back is held off until this time; see {@link #settlePreview()}. */
  private long previewSettleUntil;

  private static String shutterText(long ns, boolean dial) {
    if (ns <= 0) return "";
    if (ns >= 900_000_000L) {
      double sec = ns / 1e9;
      return (Math.abs(sec - Math.rint(sec)) < .05
              ? String.valueOf(Math.round(sec))
              : String.format(Locale.US, "%.1f", sec))
          + "\"";
    }
    long d = Math.round(1e9 / ns);
    return dial ? String.valueOf(d) : "1/" + d;
  }

  private static int evIndex(float value) {
    return Math.max(0, Math.min(12, Math.round(value * 3) + 6));
  }

  private static int indexOf(long[] values, long value) {
    if (value <= 0) return 0;
    for (int i = 0; i < values.length; i++) if (values[i] == value) return i + 1;
    return 0;
  }

  private static int indexOf(int[] values, int value) {
    if (value <= 0) return 0;
    for (int i = 0; i < values.length; i++) if (values[i] == value) return i + 1;
    return 0;
  }

  private static String onOff(boolean value) {
    return value ? "ON" : "OFF";
  }

  private static String shortLabel(String label) {
    return label
        .toUpperCase(Locale.ROOT)
        .replace("FIRST VERSION", "FIRST")
        .replace("KELVIN ONLY", "K ONLY");
  }

  /** The photo ratio as the camera back is held: 3:4 upright, 4:3 sideways. */
  private String orientedRatio() {
    boolean land = uiRotation == 90 || uiRotation == 270;
    if (!land || "1:1".equals(ratio)) return ratio;
    String[] parts = ratio.split(":");
    return parts.length == 2 ? parts[1] + ":" + parts[0] : ratio;
  }

  /** Shutter speeds and ISOs this camera takes, and the dials' labels. */
  private void updateDials() {
    if (back == null) return;
    org.json.JSONArray times = capabilities.optJSONArray("exposureTimeRangeNs");
    org.json.JSONArray isos = capabilities.optJSONArray("isoRange");
    long tMin = times == null ? 0 : times.optLong(0), tMax = times == null ? Long.MAX_VALUE : times.optLong(1, Long.MAX_VALUE);
    int iMin = isos == null ? 0 : isos.optInt(0), iMax = isos == null ? Integer.MAX_VALUE : isos.optInt(1, Integer.MAX_VALUE);
    List<Long> speeds = new ArrayList<>();
    for (long v : SHUTTER_NS) if (v >= tMin && v <= tMax) speeds.add(v);
    shutterValues = new long[speeds.size()];
    for (int i = 0; i < shutterValues.length; i++) shutterValues[i] = speeds.get(i);
    List<Integer> sensitivities = new ArrayList<>();
    for (int v : ISO_STEPS) if (v >= iMin && v <= iMax) sensitivities.add(v);
    isoValues = new int[sensitivities.size()];
    for (int i = 0; i < isoValues.length; i++) isoValues[i] = sensitivities.get(i);
    if (indexOf(shutterValues, shutterNs) == 0) shutterNs = 0;
    if (indexOf(isoValues, isoSetting) == 0) isoSetting = 0;
    int n = 1 + (isoDial ? isoValues.length : shutterValues.length);
    String[] labels = new String[n];
    boolean[] accent = new boolean[n];
    labels[0] = "A";
    accent[0] = true;
    for (int i = 1; i < n; i++)
      labels[i] = isoDial ? String.valueOf(isoValues[i - 1]) : shutterText(shutterValues[i - 1], true);
    back.shutterDial.setItems(
        labels, accent, isoDial ? indexOf(isoValues, isoSetting) : indexOf(shutterValues, shutterNs));
    boolean[] evAccent = new boolean[EV_LABELS.length];
    evAccent[EV_C] = true;
    back.exposure.setItems(EV_LABELS, evAccent, evCustom ? EV_C : evIndex(ev));
    back.setIsoMode(isoDial);
  }

  /**
   * What the still will use, from the preview's meter: {shutter ns, ISO, EV against the meter, 1
   * when the automatic half of S or ISO priority runs out of range}.
   */
  private double[] exposurePlan() {
    GrainCamera.Meter m = lastMeter;
    org.json.JSONArray times = capabilities.optJSONArray("exposureTimeRangeNs");
    org.json.JSONArray isos = capabilities.optJSONArray("isoRange");
    long tMin = times == null ? 10_000L : Math.max(1L, times.optLong(0, 10_000L));
    long tMax =
        times == null ? 1_000_000_000L : Math.max(tMin, times.optLong(1, 1_000_000_000L));
    int iMin = isos == null ? 50 : Math.max(1, isos.optInt(0, 50));
    int iMax = isos == null ? 12800 : Math.max(iMin, isos.optInt(1, 12800));
    double neutral =
        m != null && m.exposureNs > 0 && m.iso > 0
            ? m.neutralNsIso()
            : ExposureMath.DEFAULT_NEUTRAL_NS_ISO;
    ExposureMath.Plan plan =
        ExposureMath.planStill(shutterNs, isoSetting, ev, 0.0, neutral, tMin, tMax, iMin, iMax);
    if (plan != null) {
      return new double[] {plan.shutterNs, plan.iso, plan.shownEv, plan.outOfRange ? 1.0 : 0.0};
    }
    double autoT = m != null && m.exposureNs > 0 ? m.exposureNs : 0.0;
    double autoIso = m != null && m.iso > 0 ? m.iso : 0.0;
    return new double[] {autoT, autoIso, ev, 0.0};
  }

  private String controlLabel(Recipe r, FujiRecipe.Tuning t, String name) {
    for (TuningControl c : TuningControl.fujifilm(r.fuji))
      if (c.name.equals(name)) return c.enabled(t) ? shortLabel(c.labels[c.at(t)]) : null;
    return null;
  }

  /** Everything the camera's screen shows. */
  private void pushOsd() {
    if (back == null) return;
    CameraBack.OsdState o = back.state;
    Recipe r = Recipe.ALL[recipe];
    o.code = filmCode(r);
    o.film =
        neutralHeld
            ? "PHONE VIEW · PREVIEW ONLY"
            : r.name.toUpperCase(Locale.ROOT) + (tuning.tuned(r) ? " · TUNED" : "");
    o.format = r.raw && keepRawFiles ? "FINE+RAW" : "FINE";
    o.ratio = orientedRatio();
    o.frames = frameCount >= 0 ? String.valueOf(frameCount) : "";
    o.battery = batteryLevel;
    o.icons.clear();
    if (r.raw) {
      FujiRecipe.Tuning t = tuning.effective(r).tuning();
      String dr = controlLabel(r, t, "Dynamic range");
      if (dr != null) o.icons.add(dr.startsWith("DR") ? dr : "DR " + dr);
      String wb = controlLabel(r, t, "White balance");
      if (wb != null) {
        String kelvin = wb.startsWith("K") ? controlLabel(r, t, "Color temperature") : null;
        o.icons.add(kelvin != null ? kelvin : "WB " + wb);
      }
      if (faceExposure) o.icons.add("FACE");
    } else o.icons.add("ISP " + strengths[recipe] + "%");
    o.mfFraction = mfStep / (float) MF_STEPS;
    if (mfOn) o.icons.add("MF");
    else if (afArea == GrainCamera.AF_FACE)
      o.icons.add(focusMode == FocusMath.FOCUS_AFS ? "AF-S" : "AF-C");
    else if (focusMode == FocusMath.FOCUS_AFS) o.icons.add("AF-S");
    if (flashMode != ExposureMath.FLASH_OFF) o.icons.add(ExposureMath.FLASH_SHORT[flashMode]);
    double[] plan = exposurePlan();
    o.mode = ExposureMath.modeLabel(shutterNs, isoSetting);
    o.shutter = shutterText((long) plan[0], false);
    double aperture = capabilities.optDouble("aperture", 0);
    o.aperture = aperture > 0 ? String.format(Locale.US, "F%.1f", aperture) : "";
    o.iso = plan[1] > 0 ? String.valueOf(Math.round(plan[1])) : "";
    o.isoAuto = isoSetting == 0;
    o.manual = shutterNs > 0 || isoSetting > 0;
    o.exposure = plan[2];
    o.exposureWarn = plan[3] > 0;
    o.hiResGuide = hiResActive;
    o.hiResPhaseMask = hiResPhaseMask;
    o.hiResPhases = hiResPhases;
    o.hiResFrames = hiResFramesFor(hiResMode);
    o.hiResShiftPx = hiResShiftPx;
    o.drive =
        drive == DRIVE_SINGLE
            ? ""
            : drive == DRIVE_FS_BKT
                ? "FS BKT"
                : drive == DRIVE_FOCUS_BKT
                    ? "BKT " + FocusMath.BKT_COUNTS[bktFramesIndex]
                    : isHiRes(drive)
                        ? "HR " + hiResMegapixels(drive) + "MP"
                        : "◷ " + timerSeconds + "S";
    o.zoom = zoom > 1.04 ? String.format(Locale.US, "%.1f×", zoom) : "";
    o.updateAvailable = availableUpdate != null && !availableUpdate.tagName.equals(skippedUpdateTag);
    o.aeLock = aeLock;
    o.gridStyle = gridStyle;
    o.streaming = remote != null && remote.isLive();
    o.showHistogram = showHistogram;
    o.showLevel = showLevel;
    o.infoOff = dispMode == 1;
    o.histogram = showHistogram && ready ? histogramBins : null;
    o.afX = afX;
    o.afY = afY;
    o.afArea = afArea;
    if (afArea != GrainCamera.AF_FACE) o.faceActive = false;
    o.afState = afFeedback;
    o.busy = busy;
    back.refresh();
  }

  /** Camera-axis pitch (front/back tilt) in degrees, smoothed; feeds the Sony-style chevron. */
  private void pitch(float sine) {
    if (back == null) return;
    float degrees = (float) Math.toDegrees(Math.asin(sine));
    pitchSmooth = Float.isNaN(pitchSmooth) ? degrees : pitchSmooth + (degrees - pitchSmooth) * .35f;
    if (Float.isNaN(back.state.pitch) || Math.abs(pitchSmooth - back.state.pitch) >= .2f) {
      back.state.pitch = pitchSmooth;
      if (showLevel) back.refresh();
    }
  }

  /** Electronic level, smoothed; NaN hides it (phone flat). */
  /** Start/stop the Wi-Fi remote viewfinder sender (the grain-remote companion app connects). */
  private final ActivityResultLauncher<Intent> remoteConsent =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
              Intent svc =
                  new Intent(this, RemoteService.class)
                      .setAction(RemoteService.ACTION_START)
                      .putExtra("code", r.getResultCode())
                      .putExtra("data", r.getData());
              startForegroundService(svc);
              // RemoteService runs the onReady hook once the projection exists
            } else {
              remoteOn = false;
              if (back != null) back.toast("REMOTE VIEW", "screen-capture permission is needed");
              pushOsd();
            }
          });

  private void startRemote() {
    if (remote != null) return;
    if (remoteToken.isEmpty()) remoteToken = RemoteStream.newToken();
    // lifecycle hooks for the projection-holding foreground service
    RemoteService.setOnReady(() -> { if (remoteOn && remote == null) startRemote(); });
    RemoteService.setOnStopped(this::stopRemote);
    MediaProjection mp = RemoteService.current();
    if (mp == null) {
      // first time (or after a restart): ask Android's screen-capture consent once,
      // the service continues via setOnReady
      remoteConsent.launch(
          getSystemService(MediaProjectionManager.class).createScreenCaptureIntent());
      return;
    }
    // stream the full screen (the app is portrait-locked): the PC mirrors exactly what you see
    android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
    getWindowManager().getDefaultDisplay().getRealMetrics(dm);
    int h = 1080, w = Math.max(16, Math.round(h * dm.widthPixels / (float) dm.heightPixels / 16f) * 16);
    remote =
        new RemoteStream(
            () -> {
              StringBuilder j = new StringBuilder(192);
              j.append('{');
              if (back != null) {
                j.append("\"iso\":\"").append(back.state.iso).append('\"');
                j.append(",\"ss\":\"").append(back.state.shutter).append('\"');
                j.append(",\"f\":\"").append(back.state.aperture).append('\"');
                j.append(",\"ev\":").append(String.format(Locale.US, "%.2f", back.state.exposure));
              }
              j.append(",\"recipe\":\"").append(Recipe.ALL[recipe].name.replace("\"", "'")).append('\"');
              j.append(",\"grid\":").append(gridStyle);
              j.append(",\"af\":\"").append(AF_NAMES[Math.min(afArea, AF_NAMES.length - 1)]).append('\"');
              j.append(",\"afx\":").append(String.format(Locale.US, "%.3f", afX));
              j.append(",\"afy\":").append(String.format(Locale.US, "%.3f", afY));
              j.append(",\"roll\":").append(Float.isNaN(levelSmooth) ? "null" : String.format(Locale.US, "%.1f", levelSmooth));
              j.append(",\"pitch\":").append(Float.isNaN(pitchSmooth) ? "null" : String.format(Locale.US, "%.1f", pitchSmooth));
              j.append(",\"rot\":0,\"cap\":\"screen\""); // screen capture arrives upright already
              j.append(",\"shot\":").append(busy || saving ? 1 : 0);
              return j.append('}').toString();
            },
            mp,
            w,
            h,
            dm.densityDpi);
    final String describe = RemoteStream.describe(RemoteStream.localIp(), remoteToken);
    remote.start(
        remoteToken,
        () -> {
          back.toast("REMOTE VIEW", "stream failed — stopped");
          stopRemote();
        });
    back.toast("REMOTE VIEW ON", describe + " — run grain-remote.exe on the PC");
    pushOsd();
  }

  private void stopRemote() {
    if (remote != null) remote.stop();
    remote = null;
    pushOsd();
  }

  private void level(float tilt) {
    if (back == null) return;
    if (Float.isNaN(tilt)) {
      levelSmooth = Float.NaN;
      pitchSmooth = Float.NaN;
      back.state.pitch = Float.NaN;
      if (!Float.isNaN(back.state.level)) {
        back.state.level = Float.NaN;
        back.refresh();
      }
      return;
    }
    levelSmooth = Float.isNaN(levelSmooth) ? tilt : levelSmooth + (tilt - levelSmooth) * .35f;
    if (Float.isNaN(back.state.level) || Math.abs(levelSmooth - back.state.level) >= .2f) {
      back.state.level = levelSmooth;
      if (showLevel) back.refresh();
    }
  }

  private void readBattery() {
    BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
    int capacity = battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    batteryLevel = capacity <= 0 || capacity > 100 ? -1 : capacity;
  }

  /** Live histogram of the preview (a 48 × 64 read-back three times a second) and the battery. */
  private final Runnable histogramTask =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          main.postDelayed(this, 300);
          if (++histogramTicks % 200 == 0) {
            readBattery();
            pushOsd();
          }
          boolean manualActive = shutterNs > 0 || isoSetting > 0;
          boolean wantHistogram = showHistogram && dispMode == 0;
          if ((!wantHistogram && !manualActive)
              || !ready
              || !powerOn
              || back == null
              || !"camera".equals(mode)
              || texture == null
              || !texture.isAvailable()) return;
          if (histogramBitmap == null) {
            histogramBitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888);
            histogramPixels = new int[48 * 64];
          }
          Bitmap b = readPreview(histogramBitmap);
          if (b == null) return;
          b.getPixels(histogramPixels, 0, 48, 0, 0, 48, 64);
          if (manualActive && camera != null) {
            camera.reportPreviewLuma(ExposureMath.meanLinearLuma(histogramPixels, 48, 64));
          }
          if (wantHistogram) {
            Arrays.fill(histogramBins, 0);
            for (int c : histogramPixels) {
              int luma = (((c >> 16) & 255) * 54 + ((c >> 8) & 255) * 183 + (c & 255) * 19) >> 8;
              histogramBins[Math.min(63, luma >> 2)]++;
            }
            back.state.histogram = histogramBins;
            back.refresh();
          }
        }
      };

  /**
   * Reads the live preview back into {@code target}, or returns null when there is nothing to read.
   *
   * <p>Reading a TextureView back needs its hardware layer, and the platform throws instead of
   * returning null when it cannot get one — the menu overlay can be up, the surface can be torn
   * down, the camera session can be restarting. It can also hand back a bitmap of a different size
   * if the preview was resized underneath us. None of that is worth taking the app down for.
   *
   * <p>The histogram, the loupe and the peaking all read the same pixels, so they all come through
   * here: they simply get nothing on the frames they cannot read.
   */
  private Bitmap readPreview(Bitmap target) {
    lastReadFailed = false;
    try {
      if (texture == null || target == null) return null;
      if (!PreviewMath.canReadBack(
          SystemClock.uptimeMillis(),
          previewSettleUntil,
          texture.isAvailable(),
          texture.getWidth(),
          texture.getHeight()))
        return null;
      Bitmap b = texture.getBitmap(target);
      if (b == null) return null;
      if (!PreviewMath.fitsTarget(b.getWidth(), b.getHeight(), target.getWidth(), target.getHeight()))
        return null;
      return b;
    } catch (Throwable t) {
      lastReadFailed = true;
      return null;
    }
  }

  /** Holds the preview read-back off for a moment after the view tree changes. */
  private void settlePreview() {
    previewSettleUntil = SystemClock.uptimeMillis() + PREVIEW_SETTLE_MS;
  }

  private void startPeaking() {
    main.removeCallbacks(peakingTask);
    main.post(peakingTask);
  }

  private void stopPeaking() {
    main.removeCallbacks(peakingTask);
    if (back != null && back.state.peak != null) {
      back.state.peak = null;
      back.refresh();
    }
  }

  /**
   * Peaking (v0.5.0 plan): a small read-back of the preview, turned into an edge map and drawn back
   * over the picture, so you can see what is sharp. It is the manual-focus aid: with the autofocus
   * off there is nothing else telling you where the lens actually is. Mid-tones only — pure white and
   * pure black carry no focus information, and highlighting them would just glow.
   */
  private final Runnable peakingTask =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          main.postDelayed(this, 140);
          if (!peaking || !ready || !powerOn || back == null || texture == null
              || !texture.isAvailable() || !("camera".equals(mode)) || dispMode == 1) {
            if (back != null && back.state.peak != null) {
              back.state.peak = null;
              back.refresh();
            }
            return;
          }
          try {
            if (peakBitmap == null) {
              peakBitmap = Bitmap.createBitmap(PEAK_W, PEAK_H, Bitmap.Config.ARGB_8888);
              peakPixels = new int[PEAK_W * PEAK_H];
              peakLuma = new int[PEAK_W * PEAK_H];
              peakOut = new int[PEAK_W * PEAK_H];
            }
            Bitmap b = readPreview(peakBitmap);
            if (b == null) {
              // The preview could not be read back this frame. Peaking is an aid, not the camera: it
              // must never take the app down with it, so after a run of failures it switches off.
              if (lastReadFailed && ++peakFailures > PEAK_MAX_FAILURES) {
                peakFailures = 0;
                peaking = false;
                savePrefs();
                stopPeaking();
                back.toast("PEAKING", "preview read-back unavailable on this device");
              }
              return;
            }
            peakFailures = 0;
            b.getPixels(peakPixels, 0, PEAK_W, 0, 0, PEAK_W, PEAK_H);
            for (int i = 0; i < peakPixels.length; i++) {
              int c = peakPixels[i];
              peakLuma[i] = (((c >> 16) & 255) * 54 + ((c >> 8) & 255) * 183 + (c & 255) * 19) >> 8;
            }
            for (int y = 0; y < PEAK_H; y++) {
              int yU = Math.min(PEAK_H - 1, y + 1) * PEAK_W, yD = Math.max(0, y - 1) * PEAK_W;
              for (int x = 0; x < PEAK_W; x++) {
                int i = y * PEAK_W + x;
                int dx = peakLuma[i + Math.min(PEAK_W - 1, x + 1)] - peakLuma[i + Math.max(0, x - 1)];
                int dy = peakLuma[yU + x] - peakLuma[yD + x];
                int l = peakLuma[i];
                int mag = l > 24 && l < 232 ? Math.min(255, Math.abs(dx) + Math.abs(dy)) : 0;
                peakOut[i] = (mag << 24) | 0xFFFFFF;
              }
            }
            peakBitmap.setPixels(peakOut, 0, PEAK_W, 0, 0, PEAK_W, PEAK_H);
            back.state.peak = peakBitmap;
            back.refresh();
          } catch (Throwable t) {
            // Peaking is an aid, not the camera: nothing in here is worth taking the app down
            // for. After a run of failures it switches itself off and says so.
            if (++peakFailures > PEAK_MAX_FAILURES) {
              peakFailures = 0;
              peaking = false;
              savePrefs();
              stopPeaking();
              back.toast("PEAKING", "preview read-back unavailable on this device");
            }
          }
        }
      };

  private boolean magnifierActive() {
    if (!powerOn || busy || counting || back == null || !"camera".equals(page) || !"camera".equals(mode))
      return false;
    if (magnifier == FocusMath.MAG_4X_ON) return true;
    return magHeld && (magnifier == FocusMath.MAG_4X_HOLD || magnifier == FocusMath.MAG_8X_HOLD);
  }

  private String currentMagLabel() {
    int factor = FocusMath.magFactor(magnifier);
    if (factor <= 1) return "";
    if (!mfOn) return factor + "×";
    float min = camera == null ? 0f : camera.minFocusDistance();
    if (min > 0f) return factor + "× · " + FocusScale.label(mfStep / (float) MF_STEPS, min);
    return factor + "× · " + (mfStep == 0 ? "INF" : mfStep + "/" + MF_STEPS);
  }

  /** The distance the lens is focused at, read off the lens's own dioptre travel. */
  private String mfLabel() {
    float min = camera == null ? 0f : camera.minFocusDistance();
    if (min > 0f) return FocusScale.label(mfStep / (float) MF_STEPS, min);
    return mfStep == 0 ? "INFINITY" : "STEP " + mfStep + " / " + MF_STEPS;
  }

  private void syncMagnifier() {
    if (magnifierActive()) {
      main.removeCallbacks(magnifierTask);
      main.post(magnifierTask);
    } else {
      stopMagnifier();
    }
  }

  private void stopMagnifier() {
    main.removeCallbacks(magnifierTask);
    magHeld = false;
    if (back != null && back.state.mag != null) {
      back.state.mag = null;
      back.state.magLabel = "";
      back.refresh();
    }
  }

  /**
   * Focus Check magnifier: samples the live TextureView around the active focus point (or tracked
   * face) at {@code 4×} or {@code 8×} magnification, rotating into the OSD's upright orientation and
   * optionally overlaying mid-tone peaking edges when Peaking is enabled.
   */
  private final Runnable magnifierTask =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed || !magnifierActive()) {
            stopMagnifier();
            return;
          }
          main.postDelayed(this, 65);
          if (magPatch == null) {
            magPatch = Bitmap.createBitmap(MAG_OUT, MAG_OUT, Bitmap.Config.ARGB_8888);
            magCanvas = new Canvas(magPatch);
          }
          magCanvas.drawColor(0xff101110);
          int factor = FocusMath.magFactor(magnifier);
          if (texture != null && texture.isAvailable() && frame != null && frame.getWidth() > 0 && frame.getHeight() > 0) {
            if (magSource == null) {
              magSource = Bitmap.createBitmap(MAG_SRC_W, MAG_SRC_H, Bitmap.Config.ARGB_8888);
            }
            Bitmap src = readPreview(magSource);
            if (src != null) {
              float mx =
                  afArea == GrainCamera.AF_FACE && back.state.faceActive
                      ? (back.state.faceL + back.state.faceR) * .5f
                      : afArea == GrainCamera.AF_CENTER ? .5f : afX;
              float my =
                  afArea == GrainCamera.AF_FACE && back.state.faceActive
                      ? (back.state.faceT + back.state.faceB) * .5f
                      : afArea == GrainCamera.AF_CENTER ? .5f : afY;
              float[] rootPt = back.imagePointToRoot(mx, my);
              float fx = rootPt[0] - frame.getLeft();
              float fy = rootPt[1] - frame.getTop();
              RectF imgRect = back.screenRect();
              float patchScreen = Math.max(48f, Math.min(imgRect.width(), imgRect.height()) * .42f);
              float zoomScale = factor * (MAG_OUT / patchScreen);
              texture.getTransform(magTexMatrix);
              magMatrix.reset();
              magMatrix.postScale(
                  frame.getWidth() / (float) MAG_SRC_W,
                  frame.getHeight() / (float) MAG_SRC_H);
              magMatrix.postConcat(magTexMatrix);
              magMatrix.postTranslate(-fx, -fy);
              magMatrix.postRotate(-uiRotation);
              magMatrix.postScale(zoomScale, zoomScale);
              magMatrix.postTranslate(MAG_OUT * .5f, MAG_OUT * .5f);
              magCanvas.drawBitmap(src, magMatrix, magPaint);
              if (peaking) {
                if (magPixels == null) {
                  magPixels = new int[MAG_OUT * MAG_OUT];
                  magLuma = new int[MAG_OUT * MAG_OUT];
                }
                magPatch.getPixels(magPixels, 0, MAG_OUT, 0, 0, MAG_OUT, MAG_OUT);
                for (int i = 0; i < magPixels.length; i++) {
                  int c = magPixels[i];
                  magLuma[i] = (((c >> 16) & 255) * 54 + ((c >> 8) & 255) * 183 + (c & 255) * 19) >> 8;
                }
                for (int y = 1; y < MAG_OUT - 1; y++) {
                  int row = y * MAG_OUT;
                  for (int x = 1; x < MAG_OUT - 1; x++) {
                    int idx = row + x;
                    int l = magLuma[idx];
                    if (l <= 24 || l >= 232) continue;
                    int edge =
                        Math.abs(magLuma[idx + 1] - magLuma[idx - 1])
                            + Math.abs(magLuma[idx + MAG_OUT] - magLuma[idx - MAG_OUT]);
                    if (edge > 38) {
                      int a = Math.min(220, edge * 2);
                      int c = magPixels[idx];
                      int r = ((c >> 16) & 255) + (((255 - ((c >> 16) & 255)) * a) >> 8);
                      int g = ((c >> 8) & 255) + (((255 - ((c >> 8) & 255)) * a) >> 8);
                      int b = (c & 255) + (((255 - (c & 255)) * a) >> 8);
                      magPixels[idx] = 0xff000000 | (r << 16) | (g << 8) | b;
                    }
                  }
                }
                magPatch.setPixels(magPixels, 0, MAG_OUT, 0, 0, MAG_OUT, MAG_OUT);
              }
            }
          }
          back.state.mag = magPatch;
          back.state.magLabel = currentMagLabel();
          back.refresh();
        }
      };

  @Override
  public void meter(GrainCamera.Meter m) {
    lastMeter = m;
    if (back != null) {
      if (afArea == GrainCamera.AF_FACE && m.faceUpright != null) {
        float[] osdBox =
            FocusMath.uprightBoxToOsd(
                m.faceUpright, sourceWidth, sourceHeight, ratio, zoom, uiRotation);
        if (osdBox != null) {
          back.state.faceActive = true;
          back.state.faceL = osdBox[0];
          back.state.faceT = osdBox[1];
          back.state.faceR = osdBox[2];
          back.state.faceB = osdBox[3];
        } else {
          back.state.faceActive = false;
        }
      } else {
        back.state.faceActive = false;
      }
    }
    if (halfPressed && afFeedback == 1) {
      if (m.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
          || m.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
        afFeedback = 2;
        back.lamp.set(1);
        if (sounds && sound != null) sound.play(MediaActionSound.FOCUS_COMPLETE);
      } else if (m.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
        afFeedback = 3;
        back.lamp.set(3);
      }
    } else if (!halfPressed) {
      if (focusMode == FocusMath.FOCUS_AFC
          && afArea == GrainCamera.AF_FACE
          && back != null
          && back.state.faceActive) {
        afFeedback =
            m.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                    || m.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                ? 2
                : 0;
      } else if (afFeedback == 2 && focusMode == FocusMath.FOCUS_AFC) {
        afFeedback = 0;
      }
    }
    pushOsd();
  }

  @Override
  public void previewRect(RectF r) {
    int w = Math.round(r.width()), h = Math.round(r.height());
    int l = Math.round(r.left), t = Math.round(r.top);
    if (w <= 0 || h <= 0) return;
    if (l == previewLeft && t == previewTop && w == previewWidth && h == previewHeight) return;
    previewLeft = l;
    previewTop = t;
    previewWidth = w;
    previewHeight = h;
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
    lp.leftMargin = l;
    lp.topMargin = t;
    frame.setLayoutParams(lp);
    // The preview surface is about to be resized; reading it back before it has settled is what
    // used to take the app down.
    settlePreview();
    if (camera != null) frame.post(() -> camera.updateTransform(zoom));
  }

  @Override
  public void lcdTap(float rawX, float rawY, float x, float y) {
    if (!"camera".equals(mode) || busy || !powerOn) return;
    afX = Math.max(.06f, Math.min(.94f, x));
    afY = Math.max(.06f, Math.min(.94f, y));
    afFeedback = 0;
    focusAt(afX, afY, false);
    pushOsd();
  }

  @Override
  public void lcdHold(float x, float y, boolean held) {
    if (!held) {
      if (magHeld) {
        magHeld = false;
        syncMagnifier();
      }
      return;
    }
    if (!"camera".equals(mode) || busy || counting || !powerOn) return;
    if (magnifier == FocusMath.MAG_OFF) {
      back.toast("MAGNIFIER OFF", "MENU → AF / FACE → MAGNIFIER to enable");
      return;
    }
    if (afArea != GrainCamera.AF_FACE && afArea != GrainCamera.AF_CENTER) {
      afX = Math.max(.06f, Math.min(.94f, x));
      afY = Math.max(.06f, Math.min(.94f, y));
      focusAt(afX, afY, false);
    }
    magHeld = true;
    syncMagnifier();
    pushOsd();
  }

  @Override
  public void pinch(float factor) {
    if ("camera".equals(mode) && powerOn) setZoom(zoom * factor);
  }

  /** Focuses where the focus frame is: the picture point is mapped to the preview, then the sensor. */
  private void focusAt(float x, float y, boolean hold) {
    if (camera == null || !ready || busy || frame.getWidth() == 0) return;
    float[] p = back.imagePointToRoot(x, y);
    double px = (p[0] - frame.getLeft()) / frame.getWidth(), py = (p[1] - frame.getTop()) / frame.getHeight();
    double[] mapped = Framing.sourcePoint(px, py, sourceWidth, sourceHeight, ratio, zoom);
    int rotation = capabilities.optInt("sensorOrientation", 90);
    double[] sensorPt = FocusMath.uprightPointToSensor(mapped[0], mapped[1], rotation, front);
    camera.focus(sensorPt[0], sensorPt[1], hold);
  }

  @Override
  public void key(int key, int value) {
    if (!powerOn && key != CameraBack.K_POWER) return;
    switch (key) {
      case CameraBack.K_FILM:
        filmDial(value);
        break;
      case CameraBack.K_EV:
        evDial(value);
        break;
      case CameraBack.K_SS:
        speedDial(value);
        break;
      case CameraBack.K_ISO_TOGGLE:
        isoDial = !isoDial;
        savePrefs();
        updateDials();
        updateLabels();
        back.toast(isoDial ? "ISO DIAL" : "SHUTTER DIAL", isoDial ? "the top dial sets ISO" : "the top dial sets shutter speed");
        break;
      case CameraBack.K_SHUTTER:
        shutterKey(value);
        break;
      case CameraBack.K_Q:
        qKey();
        break;
      case CameraBack.K_OK:
        okKey();
        break;
      case CameraBack.K_DISP:
        dispKey();
        break;
      case CameraBack.K_PLAY:
        if ("play".equals(mode)) closeOverlays();
        else openPlayback();
        break;
      case CameraBack.K_DRIVE:
        driveKey();
        break;
      case CameraBack.K_AEL:
        if ("camera".equals(mode)) toggleAeLock();
        break;
      case CameraBack.K_LEVER:
        leverKey(value);
        break;
      case CameraBack.K_COMMAND:
        commandKey(value);
        break;
      case CameraBack.K_EYE:
        if (viewMode == 2) {
          viewMode = 0;
          savePrefs();
          back.viewMode.setText("EYE");
        }
        setEvf(!evf);
        break;
      case CameraBack.K_VIEW_MODE:
        viewMode = (viewMode + 1) % VIEW_MODES.length;
        savePrefs();
        applyViewMode(true);
        break;
      case CameraBack.K_FLASH:
        cycleFlash(1, true);
        break;
      case CameraBack.K_POWER:
        setPower(value == 1);
        break;
      case CameraBack.K_MEMO:
        memoKey(value);
        break;
      case CameraBack.K_EVF_BACK:
        setEvf(false);
        break;
      case CameraBack.K_SWIPE:
        if ("play".equals(mode)) browse(value);
        break;
      default:
        break;
    }
  }

  private void filmDial(int index) {
    if (index < 0 || index >= dialFilms.length) return;
    if (busy || counting) {
      updateLabels();
      return;
    }
    if (dialFilms[index].id != recipe) selectRecipe(dialFilms[index].id);
  }

  private void evDial(int index) {
    if (index == EV_C) {
      evCustom = true;
      back.toast("C", "the command dial sets exposure in 0.1 EV");
    } else {
      evCustom = false;
      ev = (index - 6) / 3f;
    }
    camera.update(snapshot());
    pushOsd();
  }

  private void speedDial(int index) {
    if (index > 0 && ready && !camera.manualExposureAvailable()) {
      back.shutterDial.setIndex(0);
      message("This camera doesn’t take manual shutter speed or ISO (no Camera2 manual sensor control).");
      return;
    }
    if (isoDial) isoSetting = index <= 0 || index > isoValues.length ? 0 : isoValues[index - 1];
    else shutterNs = index <= 0 || index > shutterValues.length ? 0 : shutterValues[index - 1];
    savePrefs();
    camera.update(snapshot());
    pushOsd();
    if (back != null) {
      String modeSub =
          shutterNs > 0 && isoSetting > 0
              ? "manual exposure (M)"
              : shutterNs > 0
                  ? "shutter priority (S)"
                  : isoSetting > 0 ? "ISO priority" : "program auto (P)";
      if (isoDial) {
        back.toast(isoSetting == 0 ? "ISO  AUTO" : "ISO  " + isoSetting, modeSub);
      } else {
        back.toast(
            shutterNs == 0 ? "SHUTTER  AUTO" : "SHUTTER  " + shutterText(shutterNs, false),
            modeSub);
      }
    }
  }

  private void cycleFlash(int delta, boolean announce) {
    if (ready && camera != null && !camera.flashAvailable()) {
      flashMode = ExposureMath.FLASH_OFF;
      savePrefs();
      if (back != null) {
        back.setFlashMode(flashMode);
        back.toast(
            "NO FLASH", front ? "front camera has no LED flash" : "flash unit unavailable");
      }
      pushOsd();
      return;
    }
    flashMode = (flashMode + (delta < 0 ? 3 : 1)) % 4;
    savePrefs();
    if (back != null) back.setFlashMode(flashMode);
    if (camera != null) camera.update(snapshot());
    pushOsd();
    if (announce && back != null) {
      String sub =
          flashMode == ExposureMath.FLASH_AUTO
              ? "fires automatically in low light"
              : flashMode == ExposureMath.FLASH_ON
                  ? "always fires on capture"
                  : flashMode == ExposureMath.FLASH_TORCH
                      ? "continuous LED fill light"
                      : "flash disabled";
      back.toast("FLASH  " + ExposureMath.FLASH_NAMES[flashMode], sub);
    }
  }

  private void shutterKey(int value) {
    if (value == 1) {
      halfClosedOverlay = false;
      if (!"camera".equals(mode)) {
        closeOverlays();
        halfClosedOverlay = true;
        return;
      }
      if (!ready || busy || counting) return;
      halfPressed = true;
      if (mfOn) {
        if (magnifier == FocusMath.MAG_4X_HOLD || magnifier == FocusMath.MAG_8X_HOLD) {
          magHeld = true;
          syncMagnifier();
        }
        pushOsd();
        return;
      }
      afFeedback = 1;
      float fx =
          afArea == GrainCamera.AF_FACE && back.state.faceActive
              ? (back.state.faceL + back.state.faceR) * .5f
              : afX;
      float fy =
          afArea == GrainCamera.AF_FACE && back.state.faceActive
              ? (back.state.faceT + back.state.faceB) * .5f
              : afY;
      focusAt(fx, fy, true);
      pushOsd();
      return;
    }
    if (value == -1) {
      releaseHalf();
      return;
    }
    if (halfClosedOverlay) {
      halfClosedOverlay = false;
      return;
    }
    boolean wasHalf = halfPressed;
    halfPressed = false;
    if (magHeld) {
      magHeld = false;
      syncMagnifier();
    }
    if (!"camera".equals(mode)) {
      closeOverlays();
      return;
    }
    takePhoto();
    if (wasHalf && camera != null) camera.releaseFocus();
    afFeedback = 0;
    pushOsd();
  }

  private void releaseHalf() {
    if (halfPressed && camera != null) camera.releaseFocus();
    halfPressed = false;
    afFeedback = 0;
    if (magHeld) {
      magHeld = false;
      syncMagnifier();
    }
    if (back != null) {
      back.lamp.set(busy ? 2 : 0);
      pushOsd();
    }
  }

  private void qKey() {
    if ("q".equals(mode)) closeOverlays();
    else if ("play".equals(mode)) {
      closeOverlays();
      showGallery();
    } else if (!busy && !counting) openQ();
  }

  private void okKey() {
    switch (mode) {
      case "q":
        closeOverlays();
        break;
      case "menu":
        activateMenuItem();
        break;
      case "play":
        if (!playList.isEmpty()) {
          PhotoStore.Photo p = playList.get(playIndex);
          closeOverlays();
          showPhoto(p);
        }
        break;
      default:
        if (!busy && !counting) openMenu();
        break;
    }
  }

  private void dispKey() {
    switch (mode) {
      case "q":
      case "menu":
        closeOverlays();
        break;
      case "play":
        if (playReceipt != null)
          playReceipt.setVisibility(playReceipt.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        break;
      default:
        dispMode = (dispMode + 1) % 2;
        savePrefs();
        pushOsd();
        back.toast(dispMode == 0 ? "STANDARD" : "INFO OFF", null);
        break;
    }
  }

  private void setDrive(int value) {
    if (counting) cancelTimer();
    drive = (value % DRIVES.length + DRIVES.length) % DRIVES.length;
    timerSeconds = driveTimerSeconds(drive);
    savePrefs();
    pushOsd();
  }

  /** The self-timer length a drive mode implies (4 s for 4-frame Hi-Res, 6 s for 9-frame). */
  static int driveTimerSeconds(int drive) {
    return DriveModes.driveTimerSeconds(drive);
  }

  /** True for the tripod super-resolution drive modes. */
  static boolean isHiRes(int drive) {
    return DriveModes.isHiRes(drive);
  }

  /** The nominal output size of a tripod super-resolution drive mode, in megapixels. */
  static int hiResMegapixels(int drive) {
    return DriveModes.hiResMegapixels(drive);
  }

  /** How many RAW frames a tripod super-resolution drive mode merges. */
  static int hiResFramesFor(int drive) {
    return DriveModes.hiResFramesFor(drive);
  }

  /** The target pixel count a tripod super-resolution drive mode merges toward. */
  static long hiResTargetPixelsFor(int drive) {
    return DriveModes.hiResTargetPixelsFor(drive);
  }

  /**
   * The biggest super-resolution merge this device can hold in memory, in pixels. A merged frame
   * of N pixels costs 2N bytes of Java heap for the accumulator and 4N bytes of native memory for
   * the ARGB bitmap the developer fills, so a device that cannot hold that merges smaller rather
   * than dying half way through the sequence.
   */
  private long hiResAffordablePixels() {
    Runtime rt = Runtime.getRuntime();
    // Banded RAW development keeps quarter-res planes under ~85 MB regardless of output size, so
    // the Java heap only holds the 2N-byte Bayer frame plus ~125 MB of band/strip headroom.
    double heapRoom = Math.max(0.0, rt.maxMemory() - 125_000_000.0) / 2.0;
    double ramRoom = Double.MAX_VALUE / 8;
    try {
      ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      am.getMemoryInfo(mi);
      ramRoom = mi.availMem * 0.55 / 4.0;
    } catch (Throwable ignored) {
      // No memory info: trust the heap alone.
    }
    return (long) Math.max(12_000_000L, Math.min(heapRoom, ramRoom));
  }

  /** The pixel count this run will actually merge toward: the mode's target, or what fits. */
  private long hiResEffectiveTargetPixels() {
    return Math.min(hiResTargetPixelsFor(hiResMode), hiResAffordablePixels());
  }

  /** The linear gain a drive mode's frame count can resolve: sqrt(frames). */
  static double hiResMaxScale(int drive) {
    return DriveModes.hiResMaxScale(drive);
  }

  /** Short label for a tripod super-resolution drive mode. */
  static String hiResLabel(int drive) {
    return DriveModes.hiResLabel(drive);
  }

  private void driveKey() {
    if ("play".equals(mode)) {
      deletePlayed();
      return;
    }
    if (!"camera".equals(mode)) return;
    setDrive(drive + 1);
    back.toast(
        DRIVES[drive],
        drive == DRIVE_FS_BKT
            ? "one RAW, developed as three films"
            : drive == DRIVE_FOCUS_BKT
                ? FocusMath.BKT_COUNTS[bktFramesIndex]
                    + " focus planes · "
                    + FocusMath.BKT_STEP_NAMES[bktStepMode].toLowerCase(Locale.ROOT)
                    + " step"
                : isHiRes(drive)
                    ? driveTimerSeconds(drive)
                        + "s tripod settle · merges "
                        + hiResFramesFor(drive)
                        + " RAWs into "
                        + hiResMegapixels(drive)
                        + "MP"
                    : drive == DRIVE_SINGLE
                        ? "one frame per press"
                        : "self-timer · tap the shutter to cancel");
  }

  private void toggleAeLock() {
    aeLock = !aeLock;
    camera.setAeLock(aeLock);
    camera.update(snapshot());
    pushOsd();
    back.toast(aeLock ? "AE-L" : "AE UNLOCKED", aeLock ? "exposure held until you press again" : null);
  }

  private long lastLeverFocusAt;

  private void unlockSteerableAf() {
    if (afArea == GrainCamera.AF_CENTER) {
      afX = .5f;
      afY = .5f;
      afArea = GrainCamera.AF_SINGLE;
      if (camera != null) camera.setAfArea(afArea);
    } else if (afArea == GrainCamera.AF_FACE && back != null && back.state.faceActive) {
      afX = Math.max(.06f, Math.min(.94f, (back.state.faceL + back.state.faceR) * .5f));
      afY = Math.max(.06f, Math.min(.94f, (back.state.faceT + back.state.faceB) * .5f));
      afArea = GrainCamera.AF_SINGLE;
      if (camera != null) camera.setAfArea(afArea);
    }
  }

  @Override
  public boolean leverAnalogMode() {
    return powerOn
        && !busy
        && "camera".equals(page)
        && "camera".equals(mode)
        && (back == null || back.overlay.getVisibility() != View.VISIBLE);
  }

  @Override
  public void leverGlide(float vx, float vy, float dtSec, boolean finished) {
    if (!powerOn || busy || !"camera".equals(mode)) return;
    if (dtSec > 0f && (vx != 0f || vy != 0f)) {
      unlockSteerableAf();
      float[] next = FocusMath.leverGlideStep(afX, afY, vx, vy, dtSec, 0.90f);
      afX = next[0];
      afY = next[1];
      afFeedback = 0;
      pushOsd();
    }
    long now = SystemClock.uptimeMillis();
    if (finished || now - lastLeverFocusAt >= 150L) {
      lastLeverFocusAt = now;
      focusAt(afX, afY, false);
      if (finished) pushOsd();
    }
  }

  private void leverKey(int dir) {
    switch (mode) {
      case "q":
        if (dir == CameraBack.LEVER_CENTER) closeOverlays();
        else {
          int row = qIndex / 4, col = qIndex % 4;
          if (dir == CameraBack.LEVER_UP) row = (row + 3) % 4;
          else if (dir == CameraBack.LEVER_DOWN) row = (row + 1) % 4;
          else if (dir == CameraBack.LEVER_LEFT) col = (col + 3) % 4;
          else col = (col + 1) % 4;
          qIndex = row * 4 + col;
          refreshQ();
        }
        break;
      case "menu":
        if (dir == CameraBack.LEVER_CENTER) activateMenuItem();
        else if (dir == CameraBack.LEVER_LEFT || dir == CameraBack.LEVER_RIGHT) {
          menuTab = (menuTab + (dir == CameraBack.LEVER_LEFT ? MENU_TABS.length - 1 : 1)) % MENU_TABS.length;
          menuIndex = 0;
          refreshMenu();
        } else {
          int n = menuItems(menuTab).size();
          menuIndex = (menuIndex + (dir == CameraBack.LEVER_UP ? n - 1 : 1)) % Math.max(1, n);
          refreshMenu();
        }
        break;
      case "play":
        if (dir == CameraBack.LEVER_LEFT || dir == CameraBack.LEVER_UP) browse(-1);
        else if (dir == CameraBack.LEVER_RIGHT || dir == CameraBack.LEVER_DOWN) browse(1);
        else okKey();
        break;
      default:
        if (dir == CameraBack.LEVER_CENTER) {
          afX = .5f;
          afY = .5f;
        } else {
          unlockSteerableAf();
          float step = .07f;
          if (dir == CameraBack.LEVER_UP) afY -= step;
          else if (dir == CameraBack.LEVER_DOWN) afY += step;
          else if (dir == CameraBack.LEVER_LEFT) afX -= step;
          else afX += step;
          afX = Math.max(.06f, Math.min(.94f, afX));
          afY = Math.max(.06f, Math.min(.94f, afY));
        }
        afFeedback = 0;
        focusAt(afX, afY, false);
        pushOsd();
        break;
    }
  }

  @Override
  public boolean commandContinuousZoom() {
    return powerOn && "play".equals(mode);
  }

  @Override
  public void commandSlide(float deltaUnits) {
    if ("play".equals(mode)) {
      zoomPlayedByFactor((float) Math.pow(2.0, deltaUnits / 56.0));
    }
  }

  private void commandKey(int steps) {
    switch (mode) {
      case "q":
        stepQ(steps);
        break;
      case "menu":
        stepMenu(steps);
        break;
      case "play":
        zoomPlayedByFactor((float) Math.pow(1.22, steps));
        break;
      default:
        if (evCustom) {
          ev = Math.max(-2, Math.min(2, Math.round((ev + steps * .1f) * 10) / 10f));
          camera.update(snapshot());
          pushOsd();
          back.toast(String.format(Locale.US, "%+.1f EV", ev), null);
        } else if (mfOn && magnifierActive()) {
          mfStep = Math.max(0, Math.min(MF_STEPS, mfStep + steps));
          camera.setFocusFraction(mfStep / (float) MF_STEPS);
          camera.update(snapshot());
          savePrefs();
          back.state.magLabel = currentMagLabel();
          pushOsd();
          back.toast("MF " + mfLabel(), "manual focus distance");
        } else setZoom(Math.round((zoom + steps * .1) * 10) / 10.0);
        break;
    }
  }

  private void setEvf(boolean on) {
    if (on == evf || back == null) return;
    if (on && !"camera".equals(mode)) {
      closeOverlays();
    }
    if (!on && viewMode == 1) {
      viewMode = 0;
      savePrefs();
      back.viewMode.setText("EYE");
    }
    evf = on;
    back.setEvf(on);
    pushOsd();
  }

  private void applyViewMode(boolean announce) {
    if (back == null) return;
    back.viewMode.setText(viewMode == 0 ? "EYE" : viewMode == 1 ? "EVF" : "LCD");
    if (viewMode == 1) setEvf(true);
    else if (viewMode == 2) setEvf(false);
    if (announce)
      back.toast(
          VIEW_MODES[viewMode],
          viewMode == 0 ? "tap the eyepiece for the viewfinder" : viewMode == 1 ? "viewfinder view only" : "screen view only");
  }

  private void setPower(boolean on) {
    if (on == powerOn) return;
    if (!on && (busy || saving)) {
      message("Still saving the last moment…");
      return;
    }
    powerOn = on;
    back.setOn(on);
    if (!on) {
      cancelTimer();
      closeOverlays();
      if (halfPressed) releaseHalf();
      if (camera != null) {
        camera.neutralPreview(false);
        camera.close();
      }
      ready = false;
      frame.setVisibility(View.INVISIBLE);
    } else {
      frame.setVisibility(View.VISIBLE);
      openCamera();
    }
    pushOsd();
  }

  private void memoKey(int value) {
    Recipe r = Recipe.ALL[recipe];
    if (value == 1) {
      if (!busy && ready) {
        neutralHeld = true;
        camera.neutralPreview(true);
        pushOsd();
      }
    } else if (value == 2) {
      if (neutralHeld) {
        neutralHeld = false;
        camera.neutralPreview(false);
        pushOsd();
      }
    } else
      message(
          r.name
              + (r.raw
                  ? " · Fujifilm " + r.fuji.filmLabel + (tuning.tuned(r) ? " · tuned" : "") + " · developed from RAW"
                  : " · phone ISP look")
              + " · hold the memo to see the phone’s own view");
  }

  private void flipCamera() {
    if (busy) return;
    cancelTimer();
    front = !front;
    ready = false;
    camera.close();
    main.postDelayed(this::openCamera, 350);
  }

  private void reopenCamera() {
    if (busy) return;
    ready = false;
    camera.close();
    main.postDelayed(this::openCamera, 350);
  }

  private void cycleRatio(int d) {
    if (busy) return;
    int i = Arrays.asList(Framing.RATIOS).indexOf(ratio);
    ratio = Framing.RATIOS[(Math.max(0, i) + (d < 0 ? Framing.RATIOS.length - 1 : 1)) % Framing.RATIOS.length];
    savePrefs();
    layoutFrame();
    camera.update(snapshot());
    pushOsd();
  }

  private void closeOverlays() {
    if (back != null) back.hideOverlay();
    mode = "camera";
    syncWindowColorMode();
    // Closing an overlay re-lays out the camera back and rebuilds the preview surface, and reading
    // the preview back in that window is what used to take the app down. Hold the read-back off
    // until it has settled.
    settlePreview();
    qTiles.clear();
    menuList = null;
    menuTabs = null;
    menuScroll = null;
    playImage = null;
    playInfo = null;
    playReceipt = null;
    playZoom = 1f;
    playPanX = 0f;
    playPanY = 0f;
    playBaseInfo = "";
    syncMagnifier();
    pushOsd();
  }

  // ───────────────────────────── Q menu ─────────────────────────────

  private static final String[][] Q_ITEMS = {
    {"FILM", "film"}, {"WB", "White balance"}, {"WB R", "WB shift red"}, {"WB B", "WB shift blue"},
    {"DR", "Dynamic range"}, {"H TONE", "Highlight"}, {"S TONE", "Shadow"}, {"COLOR", "Color"},
    {"SHARP", "Sharpness"}, {"NR", "High ISO NR"}, {"CLARITY", "Clarity"}, {"GRAIN", "Grain"},
    {"CCE", "Color Chrome Effect"}, {"FX BLUE", "Color Chrome FX Blue"}, {"DEV", "Development"},
    {"FACE", "face"}
  };

  /**
   * Q tiles for iPhone looks: WB, its shifts and Grain stay Fujifilm-style; the other ten tiles are
   * the gallery-editor sliders from {@link AppleTune}, adjusted in steps of 5.
   */
  private static final String[][] APPLE_Q_ITEMS = {
    {"FILM", "film"}, {"WB", "White balance"}, {"WB R", "WB shift red"}, {"WB B", "WB shift blue"},
    {"EXP", "apple.exposure"}, {"HI", "apple.highlights"}, {"SHDW", "apple.shadows"}, {"SAT", "apple.saturation"},
    {"SHARP", "apple.sharpness"}, {"NR", "apple.noiseReduction"}, {"CLAR", "apple.clarity"}, {"GRAIN", "Grain"},
    {"VIBR", "apple.vibrance"}, {"WARM", "apple.warmth"}, {"CNTR", "apple.contrast"}, {"FACE", "face"}
  };

  /** The AppleTune editor field behind a Q tile of an iPhone look, or null for WB/grain/film/face. */
  private static String appleQKey(Recipe r, int index) {
    if (!r.raw || !r.fuji.apple()) return null;
    String key = APPLE_Q_ITEMS[index][1];
    return key.startsWith("apple.") ? key.substring(6) : null;
  }

  private static int appleTuneValue(AppleTune t, String key) {
    switch (key) {
      case "exposure": return t.exposure;
      case "highlights": return t.highlights;
      case "shadows": return t.shadows;
      case "saturation": return t.saturation;
      case "sharpness": return t.sharpness;
      case "noiseReduction": return t.noiseReduction;
      case "clarity": return t.clarity;
      case "vibrance": return t.vibrance;
      case "warmth": return t.warmth;
      case "contrast": return t.contrast;
      default: return 0;
    }
  }

  private static void appleTuneSet(AppleTune t, String key, int v) {
    switch (key) {
      case "exposure": t.exposure = v; break;
      case "highlights": t.highlights = v; break;
      case "shadows": t.shadows = v; break;
      case "saturation": t.saturation = v; break;
      case "sharpness": t.sharpness = v; break;
      case "noiseReduction": t.noiseReduction = v; break;
      case "clarity": t.clarity = v; break;
      case "vibrance": t.vibrance = v; break;
      case "warmth": t.warmth = v; break;
      case "contrast": t.contrast = v; break;
      default: break;
    }
  }

  private static String appleQTitle(String key) {
    switch (key) {
      case "exposure": return "EXPOSURE";
      case "highlights": return "HIGHLIGHTS";
      case "shadows": return "SHADOWS";
      case "saturation": return "SATURATION";
      case "sharpness": return "SHARPNESS";
      case "noiseReduction": return "NOISE REDUCTION";
      case "clarity": return "CLARITY";
      case "vibrance": return "VIBRANCE";
      case "warmth": return "WARMTH";
      case "contrast": return "CONTRAST";
      default: return key.toUpperCase(Locale.ROOT);
    }
  }

  /** The tuning control behind a Q tile, or null when this film has no such setting. */
  private TuningControl qControl(Recipe r, int index) {
    if (!r.raw) return null;
    if (r.fuji.apple()) {
      // iPhone looks: only WB / WB shifts / Grain remain live Fujifilm controls in Q; the other
      // tiles are gallery-editor sliders (@link AppleTune), handled separately.
      if (!(index == 1 || index == 2 || index == 3 || index == 11)) return null;
    }
    String name = Q_ITEMS[index][1];
    if (r.fuji.monochrome() && "Color".equals(name)) name = "Filter";
    List<TuningControl> all = new ArrayList<>(TuningControl.fujifilm(r.fuji));
    all.addAll(TuningControl.extras());
    for (TuningControl c : all) if (c.name.equals(name)) return c;
    return null;
  }

  private TuningControl namedControl(Recipe r, String name) {
    if (!r.raw) return null;
    for (TuningControl c : TuningControl.fujifilm(r.fuji)) if (c.name.equals(name)) return c;
    return null;
  }

  private String qLabel(Recipe r, int index) {
    if (r.raw && r.fuji.apple()) return APPLE_Q_ITEMS[index][0];
    if (!r.raw && index == 1) return "STRENGTH";
    if (r.raw && r.fuji.monochrome() && index == 7) return "FILTER";
    return Q_ITEMS[index][0];
  }

  private String qFullName(Recipe r, int index) {
    String key = Q_ITEMS[index][1];
    if ("film".equals(key)) return "FILM SIMULATION · " + r.name.toUpperCase(Locale.ROOT);
    if ("face".equals(key)) return "FACE EXPOSURE";
    if (!r.raw) return index == 1 ? "LOOK STRENGTH" : "NOT FOR ISP LOOKS";
    String ak = appleQKey(r, index);
    if (ak != null) return appleQTitle(ak);
    TuningControl c = qControl(r, index);
    if (r.fuji.apple()) return c == null ? "—" : c.name.toUpperCase(Locale.ROOT);
    return c == null ? "NOT FOR THIS FILM" : c.name.toUpperCase(Locale.ROOT);
  }

  private String qValueText(Recipe r, int index, FujiRecipe.Tuning t) {
    String key = Q_ITEMS[index][1];
    if ("film".equals(key)) return filmCode(r);
    if ("face".equals(key)) return r.raw ? onOff(faceExposure) : "—";
    if (!r.raw) return index == 1 ? strengths[recipe] + "%" : "—";
    String ak = appleQKey(r, index);
    if (ak != null) {
      int v = appleTuneValue(tuning.appleEffective(r.fuji), ak);
      return v == 0 ? "0" : v > 0 ? "+" + v : String.valueOf(v);
    }
    TuningControl c = qControl(r, index);
    if (c == null) return "—";
    if (!c.enabled(t)) return c.disabledLabel == null ? "—" : shortLabel(c.disabledLabel);
    return shortLabel(c.labels[c.at(t)]);
  }

  private void openQ() {
    stopMagnifier();
    mode = "q";
    back.showOverlay(buildQ());
    refreshQ();
  }

  private TextView qButton(String label, Runnable action) {
    TextView b = text(label, 15, 0xfff4f1e8);
    b.setBackground(bg(0xff2b2b28, 4, 0x40ffffff, 1));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private View buildQ() {
    qTiles.clear();
    LinearLayout box = column();
    box.setBackgroundColor(0xe0101010);
    box.setPadding(dp(6), dp(4), dp(6), dp(4));
    box.setClickable(true);
    TextView head = text("Q   QUICK MENU", 8.5f, 0xffd8d3c7);
    head.setLetterSpacing(.14f);
    head.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    box.addView(head, new LinearLayout.LayoutParams(-1, dp(20)));
    for (int row = 0; row < 4; row++) {
      LinearLayout line = row();
      box.addView(line, new LinearLayout.LayoutParams(-1, 0, 1));
      for (int col = 0; col < 4; col++) {
        int index = row * 4 + col;
        LinearLayout tile = column();
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(2), dp(1), dp(2), dp(1));
        TextView label = text(Q_ITEMS[index][0], 7.5f, 0xffb9b4a8);
        label.setLetterSpacing(.08f);
        label.setSingleLine(true);
        TextView value = text("", 13, 0xfff4f1e8);
        value.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        value.setSingleLine(true);
        value.setAutoSizeTextTypeUniformWithConfiguration(7, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        tile.addView(label, new LinearLayout.LayoutParams(-1, -2));
        tile.addView(value, new LinearLayout.LayoutParams(-1, 0, 1));
        tile.setOnClickListener(
            v -> {
              if (qIndex == index) stepQ(1);
              else {
                qIndex = index;
                refreshQ();
              }
            });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        line.addView(tile, lp);
        qTiles.add(new TextView[] {label, value});
      }
    }
    LinearLayout bar = row();
    bar.setPadding(dp(8), 0, dp(2), 0);
    qName = text("", 8.5f, 0xffe9e5da);
    qName.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    qName.setSingleLine(true);
    qName.setEllipsize(android.text.TextUtils.TruncateAt.END);
    bar.addView(qName, new LinearLayout.LayoutParams(0, -1, 1));
    bar.addView(qButton("−", () -> stepQ(-1)), size(34, 28));
    qValue = text("", 10.5f, 0xffffffff);
    qValue.setSingleLine(true);
    bar.addView(qValue, size(72, 28));
    bar.addView(qButton("+", () -> stepQ(1)), size(34, 28));
    box.addView(bar, new LinearLayout.LayoutParams(-1, dp(32)));
    qKelvinRow = row();
    qKelvinRow.setPadding(dp(8), 0, dp(2), 0);
    TextView kName = text("COLOR TEMPERATURE", 8.5f, 0xffe9e5da);
    kName.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    qKelvinRow.addView(kName, new LinearLayout.LayoutParams(0, -1, 1));
    qKelvinRow.addView(qButton("−", () -> stepKelvin(-1)), size(34, 26));
    qKelvinValue = text("", 10.5f, 0xffffffff);
    qKelvinRow.addView(qKelvinValue, size(72, 26));
    qKelvinRow.addView(qButton("+", () -> stepKelvin(1)), size(34, 26));
    box.addView(qKelvinRow, new LinearLayout.LayoutParams(-1, dp(30)));
    return box;
  }

  private void refreshQ() {
    if (!"q".equals(mode) || qTiles.isEmpty() || qName == null) return;
    Recipe r = Recipe.ALL[recipe];
    FujiRecipe.Tuning t = r.raw ? tuning.effective(r).tuning() : null;
    FujiRecipe.Tuning defaults = r.raw ? r.fuji.tuning() : null;
    for (int i = 0; i < qTiles.size(); i++) {
      TextView[] views = qTiles.get(i);
      views[0].setText(qLabel(r, i));
      views[1].setText(qValueText(r, i, t));
      TuningControl c = qControl(r, i);
      String ak = appleQKey(r, i);
      boolean usable =
          i == 0
              || (i == 15 && r.raw)
              || (!r.raw && i == 1)
              || ak != null
              || (c != null && t != null && c.enabled(t));
      boolean changed = c != null && t != null && c.enabled(t) && c.at(t) != c.at(defaults);
      if (ak != null) changed = appleTuneValue(tuning.appleEffective(r.fuji), ak) != 0;
      if (!r.raw && i == 1) changed = strengths[recipe] != 80;
      views[1].setTextColor(!usable ? 0xff6f6b63 : changed ? 0xffff9a66 : 0xfff4f1e8);
      View tile = (View) views[0].getParent();
      tile.setBackground(
          i == qIndex ? bg(0xff34342f, 3, 0xfff4f1e8, 2) : bg(0x99202020, 3, 0x30ffffff, 1));
    }
    qName.setText(qFullName(r, qIndex));
    qValue.setText(qValueText(r, qIndex, t));
    TuningControl kelvin = namedControl(r, "Color temperature");
    boolean showKelvin = qIndex == 1 && kelvin != null && t != null && kelvin.enabled(t);
    qKelvinRow.setVisibility(showKelvin ? View.VISIBLE : View.GONE);
    if (showKelvin) qKelvinValue.setText(shortLabel(kelvin.labels[kelvin.at(t)]));
  }

  private void stepQ(int delta) {
    Recipe r = Recipe.ALL[recipe];
    String key = Q_ITEMS[qIndex][1];
    if (busy) return;
    if ("film".equals(key)) {
      int at = indexOfFilm(recipe);
      int next = Math.max(0, Math.min(dialFilms.length - 1, at + (delta > 0 ? 1 : -1)));
      if (dialFilms.length > 0 && next != at) selectRecipe(dialFilms[next].id);
      refreshQ();
      return;
    }
    if ("face".equals(key)) {
      if (r.raw) {
        faceExposure = !faceExposure;
        camera.setFaceDetection(faceExposure);
        camera.update(snapshot());
        savePrefs();
        pushOsd();
      }
      refreshQ();
      return;
    }
    if (!r.raw) {
      if (qIndex == 1) {
        strengths[recipe] = Math.max(0, Math.min(100, strengths[recipe] + 10 * (delta > 0 ? 1 : -1)));
        savePrefs();
        camera.update(snapshot());
        pushOsd();
      }
      refreshQ();
      return;
    }
    String ak = appleQKey(r, qIndex);
    if (ak != null) {
      AppleTune at = tuning.appleEffective(r.fuji);
      int v = appleTuneValue(at, ak) + (delta > 0 ? 5 : -5);
      appleTuneSet(at, ak, Math.max(AppleTune.MIN, Math.min(AppleTune.MAX, v)));
      tuning.appleSave(r.fuji, at);
      updateLabels();
      refreshQ();
      return;
    }
    TuningControl c = qControl(r, qIndex);
    if (c == null) return;
    FujiRecipe.Tuning t = tuning.effective(r).tuning();
    if (!c.enabled(t)) {
      back.toast(c.name.toUpperCase(Locale.ROOT), c.disabledLabel == null ? "not used now" : c.disabledLabel);
      return;
    }
    c.set(t, c.at(t) + (delta > 0 ? 1 : -1));
    tuning.save(r, t);
    camera.update(snapshot());
    updateLabels();
  }

  private void stepKelvin(int delta) {
    Recipe r = Recipe.ALL[recipe];
    TuningControl kelvin = namedControl(r, "Color temperature");
    if (kelvin == null || busy) return;
    FujiRecipe.Tuning t = tuning.effective(r).tuning();
    if (!kelvin.enabled(t)) return;
    kelvin.set(t, kelvin.at(t) + delta);
    tuning.save(r, t);
    camera.update(snapshot());
    updateLabels();
  }

  // ───────────────────────────── MENU ─────────────────────────────

  private static final String[] MENU_TABS = {"I.Q.", "AF", "SHOOT", "SET UP"};
  /** The AF area modes by name, matching the GrainCamera AF_* constants. */
  private static final String[] AF_AREA_NAMES = {"SINGLE", "MULTI", "ZONE", "CENTER", "FACE"};
  /** Manual focus steps: one dial click moves the lens one step between infinity and its closest. */
  private static final int MF_STEPS = FocusScale.STEPS;
  /** The peaking read-back, kept small so measuring it does not cost the preview. */
  private static final int PEAK_W = 160, PEAK_H = 120;

  /**
   * How many consecutive failed preview read-backs peaking tolerates before it switches itself off.
   * At one attempt every 140 ms this is a little over four seconds of continuous failure.
   */
  private static final int PEAK_MAX_FAILURES = 30;

  /**
   * How long the preview read-back is held off after the view tree changes.
   *
   * <p>Closing the menu re-lays out the camera back, which rebuilds the preview surface. Reading
   * the preview back while that is happening is what took the app down.
   */
  private static final int PREVIEW_SETTLE_MS = 700;
  private static final String[] MENU_TITLES = {"IMAGE QUALITY", "AF / FACE", "SHOOTING", "SET UP"};

  private static final class Item {
    final String name;
    final Supplier<String> value;
    final IntConsumer change;
    final boolean opens;

    Item(String name, Supplier<String> value, IntConsumer change, boolean opens) {
      this.name = name;
      this.value = value;
      this.change = change;
      this.opens = opens;
    }
  }

  private List<Item> menuItems(int tab) {
    List<Item> items = new ArrayList<>();
    switch (tab) {
      case 0:
        items.add(new Item("FILMS & TUNING", () -> Recipe.ALL[recipe].name.toUpperCase(Locale.ROOT), d -> {
          closeOverlays();
          showFilmLab();
        }, true));
        items.add(new Item("ASPECT RATIO", this::orientedRatio, this::cycleRatio, false));
        items.add(new Item("FILE FORMAT", () -> keepRawFiles ? "FINE + DNG" : "FINE", d -> {
          keepRawFiles = !keepRawFiles;
          applyRawFilesPreference();
          savePrefs();
          pushOsd();
        }, false));
        items.add(new Item("KEEP FULL FRAME", () -> onOff(keepFull), d -> {
          keepFull = !keepFull;
          savePrefs();
          camera.update(snapshot());
        }, false));
        items.add(new Item("LARGER CAPTURE", () -> onOff(larger), d -> {
          larger = !larger;
          savePrefs();
          reopenCamera();
        }, false));
        items.add(new Item("IMPORT LUT ZIP", () -> luts.summary().toUpperCase(Locale.ROOT), d -> {
          closeOverlays();
          pickLuts(null);
        }, true));
        items.add(new Item("DOWNLOAD LUTS", () -> "›", d -> {
          closeOverlays();
          chooseLutDownload();
        }, true));
        break;
      case 1:
        items.add(new Item("FACE EXPOSURE", () -> onOff(faceExposure), d -> {
          faceExposure = !faceExposure;
          camera.setFaceDetection(faceExposure);
          camera.update(snapshot());
          savePrefs();
          pushOsd();
        }, false));
        items.add(
            new Item(
                "AF AREA",
                () -> AF_AREA_NAMES[Math.max(0, afAreas.indexOf(afArea))],
                d -> {
                  int next = afAreas.indexOf(afArea);
                  next = (next + (d < 0 ? afAreas.size() - 1 : 1)) % afAreas.size();
                  afArea = afAreas.get(next);
                  camera.setAfArea(afArea);
                  camera.update(snapshot());
                  savePrefs();
                  pushOsd();
                },
                false));
        items.add(
            new Item(
                "AF LOCK",
                () -> onOff(afLock),
                d -> {
                  afLock = !afLock;
                  camera.setAfLock(afLock);
                  camera.update(snapshot());
                  savePrefs();
                  pushOsd();
                },
                false));
        items.add(
            new Item(
                "FOCUS MODE",
                () -> FocusMath.FOCUS_MODE_NAMES[focusMode],
                d -> {
                  focusMode = (focusMode + (d < 0 ? 2 : 1)) % 3;
                  mfOn = focusMode == FocusMath.FOCUS_MANUAL;
                  camera.setFocusMode(focusMode);
                  if (mfOn) camera.setFocusFraction(mfStep / (float) MF_STEPS);
                  camera.update(snapshot());
                  savePrefs();
                  pushOsd();
                },
                false));
        items.add(
            new Item(
                "MF DISTANCE",
                () -> mfOn ? mfLabel() : FocusMath.FOCUS_MODE_NAMES[focusMode],
                d -> {
                  if (!mfOn) return;
                  mfStep = Math.max(0, Math.min(MF_STEPS, mfStep + (d < 0 ? -1 : 1)));
                  camera.setFocusFraction(mfStep / (float) MF_STEPS);
                  camera.update(snapshot());
                  savePrefs();
                  pushOsd();
                },
                false));
        items.add(
            new Item(
                "MAGNIFIER",
                () -> FocusMath.MAG_NAMES[magnifier],
                d -> {
                  magnifier = (magnifier + (d < 0 ? 3 : 1)) % 4;
                  savePrefs();
                  syncMagnifier();
                  pushOsd();
                },
                false));
        items.add(
            new Item(
                "PEAKING",
                () -> onOff(peaking),
                d -> {
                  peaking = !peaking;
                  savePrefs();
                  if (peaking) startPeaking();
                  else stopPeaking();
                },
                false));
        items.add(new Item("FOCUS POINT", () -> afX == .5f && afY == .5f ? "CENTRE" : "MOVED · RESET", d -> {
          afX = .5f;
          afY = .5f;
          pushOsd();
        }, false));
        items.add(new Item("AE LOCK", () -> onOff(aeLock), d -> toggleAeLock(), false));
        break;
      case 2:
        items.add(
            new Item(
                "FRAME STACK",
                () -> stackOff ? "OFF" : "AUTOMATIC",
                d -> {
                  stackOff = !stackOff;
                  camera.setStackOff(stackOff);
                  savePrefs();
                  pushOsd();
                },
                false));
        items.add(new Item("DRIVE", () -> DRIVES[drive], d -> setDrive(drive + (d < 0 ? -1 : 1)), false));
        items.add(
            new Item(
                "FLASH",
                () -> ExposureMath.FLASH_NAMES[flashMode],
                d -> cycleFlash(d < 0 ? -1 : 1, false),
                false));
        items.add(
            new Item(
                "BKT FRAMES",
                () -> FocusMath.BKT_COUNTS[bktFramesIndex] + " FRAMES",
                d -> {
                  bktFramesIndex = (bktFramesIndex + (d < 0 ? 2 : 1)) % FocusMath.BKT_COUNTS.length;
                  savePrefs();
                  pushOsd();
                },
                false));
        items.add(
            new Item(
                "BKT STEP",
                () -> FocusMath.BKT_STEP_NAMES[bktStepMode],
                d -> {
                  bktStepMode = (bktStepMode + (d < 0 ? 2 : 1)) % FocusMath.BKT_STEP_NAMES.length;
                  savePrefs();
                },
                false));
        items.add(new Item("SHUTTER / ISO", () -> shutterNs > 0 || isoSetting > 0
            ? (shutterNs > 0 ? shutterText(shutterNs, false) : "A") + " · " + (isoSetting > 0 ? "ISO " + isoSetting : "ISO A")
            : "AUTO", d -> {
          shutterNs = 0;
          isoSetting = 0;
          savePrefs();
          updateLabels();
          camera.update(snapshot());
        }, false));
        items.add(new Item("EXPOSURE", () -> String.format(Locale.US, "%+.1f EV", ev), d -> {
          evCustom = true;
          ev = Math.max(-2, Math.min(2, Math.round((ev + .1f * (d < 0 ? -1 : 1)) * 10) / 10f));
          camera.update(snapshot());
          updateLabels();
        }, false));
        items.add(new Item("CAMERA", () -> front ? "FRONT" : "REAR", d -> flipCamera(), false));
        break;
      default:
        items.add(new Item("SOUNDS", () -> onOff(sounds), d -> {
          sounds = !sounds;
          savePrefs();
        }, false));
        items.add(new Item("VIEW MODE", () -> VIEW_MODES[viewMode], d -> {
          viewMode = (viewMode + (d < 0 ? VIEW_MODES.length - 1 : 1)) % VIEW_MODES.length;
          savePrefs();
          applyViewMode(false);
        }, false));
        items.add(new Item("SCREEN ROTATION", () -> autoRotate ? "AUTO" : "PORTRAIT", d -> {
          autoRotate = !autoRotate;
          savePrefs();
          applyUiRotation(deviceRotation, true);
        }, false));
        items.add(new Item("HISTOGRAM", () -> onOff(showHistogram), d -> {
          showHistogram = !showHistogram;
          savePrefs();
          pushOsd();
        }, false));
        items.add(new Item("ELECTRONIC LEVEL", () -> onOff(showLevel), d -> {
          showLevel = !showLevel;
          savePrefs();
          pushOsd();
        }, false));
        items.add(new Item("FRAMING GRID", () -> GRID_STYLES[gridStyle], d -> {
          gridStyle = Math.floorMod(gridStyle + (d < 0 ? GRID_STYLES.length - 1 : 1), GRID_STYLES.length);
          savePrefs();
          pushOsd();
        }, false));
        items.add(new Item("REMOTE VIEW", () -> remoteOn ? RemoteStream.describe(RemoteStream.localIp(), remoteToken) : "Off", d -> {
          remoteOn = !remoteOn;
          if (remoteOn) {
            if (RemoteStream.localIp() == null) {
              remoteOn = false;
              back.toast("REMOTE VIEW", "phone is not on Wi-Fi — join your PC's network first");
            } else startRemote();
          } else {
            stopRemote();
            // toggle-off also releases the screen-capture permission (next enable re-asks)
            startService(new Intent(this, RemoteService.class).setAction(RemoteService.ACTION_STOP));
            back.toast("REMOTE VIEW", "off");
          }
          savePrefs();
          pushOsd();
        }, false));
        items.add(new Item("BODY FINISH", () -> CameraBack.FINISHES[finish], d -> {
          finish = (finish + (d < 0 ? 2 : 1)) % 3;
          back.setFinish(finish);
          savePrefs();
        }, false));
        items.add(
            new Item(
                "APP UPDATE",
                () -> availableUpdate != null ? "NEW · " + availableUpdate.tagName : "CHECK ↗",
                d -> {
                  closeOverlays();
                  if (availableUpdate != null) showUpdateDialog(availableUpdate);
                  else checkForUpdates(true);
                },
                true));
        items.add(new Item("ALL PREFERENCES", () -> "›", d -> {
          closeOverlays();
          showSettings();
        }, true));
        items.add(new Item("INTRO GUIDE", () -> "OPEN ›", d -> {
          closeOverlays();
          showOnboarding(0);
        }, true));
        items.add(new Item("DIAGNOSTICS", () -> "›", d -> {
          closeOverlays();
          showDiagnostics();
        }, true));
        items.add(new Item("ABOUT", () -> "GRAIN " + BuildConfig.VERSION_NAME, d -> {}, false));
        break;
    }
    return items;
  }

  private void openMenu() {
    stopMagnifier();
    mode = "menu";
    menuIndex = 0;
    back.showOverlay(buildMenu());
    refreshMenu();
  }

  private View buildMenu() {
    LinearLayout box = row();
    box.setBackgroundColor(0xf2121211);
    box.setClickable(true);
    LinearLayout tabs = column();
    tabs.setBackgroundColor(0xff1c1c1a);
    tabs.setPadding(0, dp(4), 0, dp(4));
    menuTabs = new TextView[MENU_TABS.length];
    for (int i = 0; i < MENU_TABS.length; i++) {
      int tab = i;
      TextView t = text(MENU_TABS[i], 8, 0xffb9b4a8);
      t.setLetterSpacing(.06f);
      t.setOnClickListener(
          v -> {
            menuTab = tab;
            menuIndex = 0;
            refreshMenu();
          });
      tabs.addView(t, new LinearLayout.LayoutParams(-1, dp(38)));
      menuTabs[i] = t;
    }
    box.addView(tabs, new LinearLayout.LayoutParams(dp(54), -1));
    LinearLayout right = column();
    menuTitle = text("", 9, 0xfff4f1e8);
    menuTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    menuTitle.setPadding(dp(10), 0, dp(10), 0);
    menuTitle.setLetterSpacing(.12f);
    right.addView(menuTitle, new LinearLayout.LayoutParams(-1, dp(28)));
    menuScroll = new ScrollView(this);
    menuScroll.setVerticalScrollBarEnabled(false);
    menuList = column();
    menuScroll.addView(menuList);
    right.addView(menuScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView hint = text("LEVER · TAP · COMMAND DIAL CHANGES · OK SELECTS · DISP BACK", 6.5f, 0xff8f8a7f);
    hint.setSingleLine(true);
    right.addView(hint, new LinearLayout.LayoutParams(-1, dp(16)));
    box.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
    return box;
  }

  private void refreshMenu() {
    if (!"menu".equals(mode) || menuList == null || menuTabs == null) return;
    for (int i = 0; i < menuTabs.length; i++) {
      menuTabs[i].setTextColor(i == menuTab ? 0xff111111 : 0xffb9b4a8);
      menuTabs[i].setBackgroundColor(i == menuTab ? 0xfff4f1e8 : 0);
    }
    menuTitle.setText(MENU_TABS[menuTab] + "   " + MENU_TITLES[menuTab]);
    List<Item> items = menuItems(menuTab);
    menuIndex = Math.max(0, Math.min(items.size() - 1, menuIndex));
    menuList.removeAllViews();
    View selected = null;
    for (int i = 0; i < items.size(); i++) {
      Item item = items.get(i);
      int index = i;
      boolean on = i == menuIndex;
      LinearLayout line = row();
      line.setPadding(dp(10), 0, dp(10), 0);
      line.setBackgroundColor(on ? 0xfff4f1e8 : 0);
      TextView name = text(item.name, 9, on ? 0xff111111 : 0xffe9e5da);
      name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      name.setSingleLine(true);
      TextView value = text(item.value.get(), 9, on ? 0xff9a3f16 : 0xff9d988c);
      value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
      value.setSingleLine(true);
      value.setEllipsize(android.text.TextUtils.TruncateAt.END);
      line.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
      line.addView(value, new LinearLayout.LayoutParams(0, -1, .9f));
      line.setOnClickListener(
          v -> {
            if (menuIndex == index) activateMenuItem();
            else {
              menuIndex = index;
              refreshMenu();
            }
          });
      menuList.addView(line, new LinearLayout.LayoutParams(-1, dp(32)));
      if (on) selected = line;
    }
    View target = selected;
    if (target != null && menuScroll != null)
      menuScroll.post(
          () -> {
            if (menuScroll != null) menuScroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(32)));
          });
  }

  private void activateMenuItem() {
    List<Item> items = menuItems(menuTab);
    if (items.isEmpty()) return;
    Item item = items.get(Math.max(0, Math.min(items.size() - 1, menuIndex)));
    item.change.accept(1);
    refreshMenu();
  }

  private void stepMenu(int steps) {
    List<Item> items = menuItems(menuTab);
    if (items.isEmpty()) return;
    Item item = items.get(Math.max(0, Math.min(items.size() - 1, menuIndex)));
    if (item.opens) return;
    item.change.accept(steps < 0 ? -1 : 1);
    refreshMenu();
  }

  // ───────────────────────────── playback ─────────────────────────────

  private void openPlayback() {
    if (busy || counting) return;
    files.execute(
        () -> {
          List<PhotoStore.Photo> list = store.list();
          runOnUiThread(
              () -> {
                if (isDestroyed() || !powerOn) return;
                if (list.isEmpty()) {
                  back.toast("NO PHOTOS", "your photos appear here");
                  return;
                }
                playList.clear();
                playList.addAll(list);
                playIndex = 0;
                playZoom = 1f;
                playPanX = 0f;
                playPanY = 0f;
                mode = "play";
                syncWindowColorMode();
                back.showOverlay(buildPlayback());
                showPlayed();
              });
        });
  }

  private void zoomPlayedByFactor(float factor) {
    if (!"play".equals(mode) || playImage == null) return;
    float oldZoom = playZoom;
    float target = Math.max(1f, Math.min(8f, oldZoom * factor));
    if (target <= 1.015f) {
      target = 1f;
      playPanX = 0f;
      playPanY = 0f;
    } else if (oldZoom > 0.01f) {
      float ratioScale = target / oldZoom;
      playPanX *= ratioScale;
      playPanY *= ratioScale;
    }
    playZoom = target;
    applyPlayMatrix();
    updatePlayHeader();
  }

  private void applyPlayMatrix() {
    if (playImage == null) return;
    android.graphics.drawable.Drawable d = playImage.getDrawable();
    int vw = playImage.getWidth(), vh = playImage.getHeight();
    if (d == null || vw <= 0 || vh <= 0) return;
    int bw = d.getIntrinsicWidth(), bh = d.getIntrinsicHeight();
    if (bw <= 0 || bh <= 0) return;
    float[] m = FocusMath.playbackMatrixValues(vw, vh, bw, bh, playZoom, playPanX, playPanY);
    playZoom = m[3];
    playPanX = m[4];
    playPanY = m[5];
    playMatrix.reset();
    playMatrix.postScale(m[0], m[0]);
    playMatrix.postTranslate(m[1], m[2]);
    playImage.setImageMatrix(playMatrix);
  }

  private void updatePlayHeader() {
    if (playInfo == null) return;
    if (playZoom > 1.02f) {
      playInfo.setText(playBaseInfo + String.format(Locale.US, "  ·  %.1f×", playZoom));
    } else {
      playInfo.setText(playBaseInfo);
    }
  }

  private View buildPlayback() {
    FrameLayout box = new FrameLayout(this);
    box.setBackgroundColor(0xff000000);
    playImage = new ImageView(this);
    playImage.setScaleType(ImageView.ScaleType.MATRIX);
    playImage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyPlayMatrix());
    box.addView(playImage, new FrameLayout.LayoutParams(-1, -1));
    playInfo = text("", 8.5f, 0xfff4f1e8);
    playInfo.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    playInfo.setPadding(dp(8), 0, dp(8), 0);
    playInfo.setBackgroundColor(0x99000000);
    playInfo.setSingleLine(true);
    playInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
    box.addView(playInfo, new FrameLayout.LayoutParams(-1, dp(24), Gravity.TOP));
    TextView hint =
        text(
            "LEVER BROWSE · COMMAND ZOOM · DISP RECEIPT · DRIVE DEL · OK MORE · Q ALL",
            6.5f,
            0xffc9c4b8);
    hint.setBackgroundColor(0x99000000);
    hint.setSingleLine(true);
    box.addView(hint, new FrameLayout.LayoutParams(-1, dp(18), Gravity.BOTTOM));
    playReceipt = text("", 8.5f, 0xfff4f1e8);
    playReceipt.setGravity(Gravity.START | Gravity.TOP);
    playReceipt.setPadding(dp(10), dp(8), dp(10), dp(8));
    playReceipt.setBackground(bg(0xe0101010, 6, 0x40ffffff, 1));
    playReceipt.setLineSpacing(0, 1.15f);
    playReceipt.setVisibility(View.GONE);
    FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
    rp.setMargins(dp(12), dp(30), dp(12), dp(24));
    box.addView(playReceipt, rp);
    ScaleGestureDetector pinchDetector =
        new ScaleGestureDetector(
            this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector detector) {
                zoomPlayedByFactor(detector.getScaleFactor());
                return true;
              }
            });
    GestureDetector gestures =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onScroll(MotionEvent a, MotionEvent b, float distanceX, float distanceY) {
                if (playZoom > 1.01f && !pinchDetector.isInProgress()) {
                  playPanX -= distanceX;
                  playPanY -= distanceY;
                  applyPlayMatrix();
                  return true;
                }
                return false;
              }

              @Override
              public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (playZoom <= 1.01f && Math.abs(vx) > Math.abs(vy)) {
                  browse(vx < 0 ? 1 : -1);
                  return true;
                }
                return false;
              }

              @Override
              public boolean onSingleTapConfirmed(MotionEvent e) {
                dispKey();
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                if (playZoom > 1.05f) {
                  playZoom = 1f;
                  playPanX = 0f;
                  playPanY = 0f;
                  applyPlayMatrix();
                  updatePlayHeader();
                } else {
                  float cx = playImage != null ? playImage.getWidth() * 0.5f : e.getX();
                  float cy = playImage != null ? playImage.getHeight() * 0.5f : e.getY();
                  playZoom = 2.5f;
                  playPanX = (cx - e.getX()) * 1.5f;
                  playPanY = (cy - e.getY()) * 1.5f;
                  applyPlayMatrix();
                  updatePlayHeader();
                }
                return true;
              }
            });
    box.setOnTouchListener(
        (v, e) -> {
          if (v.getParent() != null && playZoom > 1.01f) {
            v.getParent().requestDisallowInterceptTouchEvent(true);
          }
          pinchDetector.onTouchEvent(e);
          gestures.onTouchEvent(e);
          if (e.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
          return true;
        });
    return box;
  }

  private void showPlayed() {
    if (!"play".equals(mode) || playList.isEmpty() || playImage == null) return;
    playIndex = Math.max(0, Math.min(playList.size() - 1, playIndex));
    PhotoStore.Photo p = playList.get(playIndex);
    playZoom = 1f;
    playPanX = 0f;
    playPanY = 0f;
    playImage.setImageDrawable(null);
    final ImageView targetView = playImage;
    files.execute(
        () -> {
          Bitmap bitmap = store.thumbnail(p.image, 3200);
          runOnUiThread(
              () -> {
                if (!isDestroyed() && targetView == playImage && targetView.isAttachedToWindow()) {
                  targetView.setImageBitmap(bitmap);
                  applyPlayMatrix();
                } else if (bitmap != null) {
                  bitmap.recycle();
                }
              });
        });
    JSONObject r = p.metadata.optJSONObject("recipe");
    playBaseInfo =
        (playIndex + 1)
            + " / "
            + playList.size()
            + "    "
            + (r == null ? "PHOTO" : r.optString("name").toUpperCase(Locale.ROOT))
            + (p.metadata.has("hiResMode")
                ? "  ·  HI-RES " + p.metadata.optInt("hiResMegapixels", 36) + "MP"
                : "")
            + (p.metadata.has("bracket") ? "  ·  FS BKT " + p.metadata.optString("bracket") : "")
            + (p.metadata.has("focusBracketSummary")
                ? "  ·  FOCUS BKT " + p.metadata.optString("focusBracketSummary")
                : "")
            + (p.dng != null ? "  ·  DNG" : "");
    updatePlayHeader();
    playReceipt.setText(receiptText(p));
  }

  private static void receiptLine(StringBuilder s, String name, String value) {
    if (value == null || value.isEmpty()) return;
    s.append(name).append("   ").append(value).append('\n');
  }

  private String receiptText(PhotoStore.Photo p) {
    JSONObject m = p.metadata;
    JSONObject r = m.optJSONObject("recipe"), a = m.optJSONObject("actual");
    JSONObject q = m.optJSONObject("requested"), d = m.optJSONObject("development");
    StringBuilder s = new StringBuilder();
    receiptLine(s, "FILE", PhotoStore.exportFileName(p, false));
    receiptLine(s, "FILM", r == null ? "" : r.optString("name"));
    if (a != null)
      receiptLine(s, "EXPOSURE", shutterText(a.optLong("exposureNs"), false) + "  ·  ISO " + a.optInt("iso"));
    JSONObject manual = q == null ? null : q.optJSONObject("manual");
    if (manual != null) receiptLine(s, "MANUAL", manual.optString("mode"));
    if (q != null && q.optBoolean("aeLock")) receiptLine(s, "AE-L", "locked");
    if (q != null && q.has("focusMode")) receiptLine(s, "FOCUS", q.optString("focusMode").toUpperCase(Locale.ROOT));
    if (d != null) {
      if (d.has("developmentGainEv"))
        receiptLine(s, "GAIN", String.format(Locale.US, "%+.2f EV", d.optDouble("developmentGainEv")));
      String face = d.optString("faceExposure", "");
      if (!face.isEmpty()) receiptLine(s, "FACE", face.length() > 48 ? face.substring(0, 48) + "…" : face);
      if (d.has("totalMs")) receiptLine(s, "DEVELOPED", String.format(Locale.US, "%.1f s", d.optLong("totalMs") / 1000.0));
    }
    if (m.has("hiResMode")) {
      receiptLine(
          s,
          "HI-RES",
          m.optInt("hiResFrames", 4)
              + " RAW frames · "
              + m.optInt("hiResMegapixels", 36)
              + "MP · "
              + m.optString("hiResPhasesFilled", "0")
              + "/9 phases · "
              + m.optString("hiResShifts", ""));
    }
    receiptLine(s, "FS BKT", m.optString("bracket", ""));
    receiptLine(s, "FOCUS BKT", m.optString("focusBracketSummary", ""));
    receiptLine(s, "SIZE", m.optInt("width") + " × " + m.optInt("height") + "  ·  " + m.optString("ratio"));
    receiptLine(s, "FILES", "JPEG" + (p.dng != null ? " + DNG" : "") + (m.optBoolean("phoneJpegKept") ? " + phone JPEG" : ""));
    return s.toString().trim();
  }

  private void browse(int delta) {
    if (!"play".equals(mode) || playList.isEmpty()) return;
    int next = Math.max(0, Math.min(playList.size() - 1, playIndex + (delta > 0 ? 1 : -1)));
    if (next == playIndex) return;
    playIndex = next;
    showPlayed();
  }

  private void deletePlayed() {
    if (playList.isEmpty()) return;
    PhotoStore.Photo p = playList.get(playIndex);
    if (!store.trash(p)) {
      message("Couldn’t delete this photo.");
      return;
    }
    int at = playIndex;
    playList.remove(at);
    updateThumb();
    if (playList.isEmpty()) closeOverlays();
    else showPlayed();
    showSnackbar(
        "Photo deleted",
        "Undo",
        () -> {
          if (!store.restore(p)) return;
          updateThumb();
          if ("play".equals(mode)) {
            playList.add(Math.min(at, playList.size()), p);
            playIndex = Math.min(at, playList.size() - 1);
            showPlayed();
          }
        });
  }

  // ───────────────────────────── In-app updates ─────────────────────────────

  private void checkForUpdates(boolean manual) {
    if (manual) message("Checking GitHub Releases for updates…");
    String token = preferences.getString("githubToken", "");
    network.execute(
        () -> {
          try {
            AppUpdater.Release rel = AppUpdater.checkLatest(BuildConfig.VERSION_NAME, null, token);
            runOnUiThread(
                () -> {
                  if (isDestroyed() || isFinishing()) return;
                  lastUpdateCheckMs = SystemClock.elapsedRealtime();
                  availableUpdate = rel;
                  pushOsd();
                  if ("menu".equals(mode)) refreshMenu();
                  if (rel == null) {
                    if (manual) {
                      back.toast(
                          "UP TO DATE",
                          "Grain v" + BuildConfig.VERSION_NAME + " is the latest release");
                    }
                    return;
                  }
                  if (manual) {
                    showUpdateDialog(rel);
                  } else if (!rel.tagName.equals(skippedUpdateTag)
                      && !rel.tagName.equals(startupNotifiedTag)
                      && resumed
                      && !busy
                      && !counting) {
                    startupNotifiedTag = rel.tagName;
                    showUpdateDialog(rel);
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (!isDestroyed() && manual) {
                    message("Update check failed: " + e.getMessage());
                    if (!isFinishing()) {
                      new AlertDialog.Builder(this)
                          .setTitle("Could not check for updates")
                          .setMessage(
                              "Automatic check failed ("
                                  + e.getMessage()
                                  + "). You can download the latest APK directly from GitHub"
                                  + " Releases and install it over Grain.")
                          .setPositiveButton(
                              "Open Releases",
                              (d, w) -> openUrl(AppUpdater.DEFAULT_WEB_RELEASES_URL + "/latest"))
                          .setNegativeButton("Cancel", null)
                          .show();
                    }
                  }
                });
          }
        });
  }

  private void showUpdateDialog(AppUpdater.Release rel) {
    if (isDestroyed() || isFinishing()) return;
    StringBuilder body = new StringBuilder();
    body.append("Installed v")
        .append(BuildConfig.VERSION_NAME)
        .append("  →  ")
        .append(rel.tagName);
    if (rel.sizeBytes > 0) {
      body.append(String.format(Locale.US, "  ·  %.1f MB", rel.sizeBytes / 1048576.0));
    }
    if (rel.notes != null && !rel.notes.isEmpty()) {
      body.append("\n\n── What's new ──\n").append(rel.notes);
    }
    if (rel.expectedSha256 != null || rel.sha256Url != null) {
      body.append("\n\nSHA-256 verified before install.");
    }
    new AlertDialog.Builder(this)
        .setTitle("Update available · Grain " + rel.tagName)
        .setMessage(body.toString())
        .setPositiveButton("Update now", (d, w) -> beginInAppUpdate(rel))
        .setNeutralButton(
            "Skip " + rel.tagName,
            (d, w) -> {
              skippedUpdateTag = rel.tagName;
              savePrefs();
              pushOsd();
            })
        .setNegativeButton("Later", null)
        .show();
  }

  private void beginInAppUpdate(AppUpdater.Release rel) {
    if (updateDownloading) {
      message("Update download is already in progress…");
      return;
    }
    if (!getPackageManager().canRequestPackageInstalls()) {
      pendingInstallRelease = rel;
      new AlertDialog.Builder(this)
          .setTitle("Allow in-app updates once")
          .setMessage(
              "Android asks permission once so Grain can install its own updates directly inside"
                  + " the app. Turn on \"Allow from this source\", and the update will begin"
                  + " automatically.")
          .setPositiveButton(
              "Allow",
              (d, w) ->
                  installPermissionLauncher.launch(
                      new Intent(
                          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                          Uri.parse("package:" + getPackageName()))))
          .setNegativeButton(
              "Cancel",
              (d, w) -> pendingInstallRelease = null)
          .show();
      return;
    }
    startUpdateDownload(rel);
  }

  private void startUpdateDownload(AppUpdater.Release rel) {
    if (updateDownloading || isDestroyed() || isFinishing()) return;
    updateDownloading = true;
    AtomicBoolean cancelled = new AtomicBoolean(false);
    LinearLayout box = column();
    box.setPadding(dp(22), dp(16), dp(22), dp(8));
    TextView label = text("Downloading " + rel.apkName + "…", 11, INK);
    label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    box.addView(label, new LinearLayout.LayoutParams(-1, -2));
    ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(100);
    bar.setIndeterminate(rel.sizeBytes <= 0);
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
    bp.topMargin = dp(12);
    box.addView(bar, bp);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Updating Grain to " + rel.tagName)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(
                "Cancel",
                (d, w) -> {
                  cancelled.set(true);
                  updateDownloading = false;
                })
            .create();
    dialog.show();
    String token = preferences.getString("githubToken", "");
    File dest = new File(new File(getCacheDir(), "updates"), rel.apkName);
    network.execute(
        () -> {
          try {
            File apk =
                AppUpdater.downloadApk(
                    rel,
                    dest,
                    token,
                    (done, total) ->
                        runOnUiThread(
                            () -> {
                              if (isDestroyed() || !dialog.isShowing()) return;
                              if (total > 0) {
                                int pct = (int) Math.min(100L, (done * 100L) / total);
                                bar.setIndeterminate(false);
                                bar.setProgress(pct);
                                label.setText(
                                    String.format(
                                        Locale.US,
                                        "Downloading %s… %d%% (%.1f / %.1f MB)",
                                        rel.tagName,
                                        pct,
                                        done / 1048576.0,
                                        total / 1048576.0));
                              } else {
                                label.setText(
                                    String.format(
                                        Locale.US,
                                        "Downloading %s… %.1f MB",
                                        rel.tagName,
                                        done / 1048576.0));
                              }
                            }),
                    cancelled);
            runOnUiThread(
                () -> {
                  updateDownloading = false;
                  updateFallbackTried = false;
                  lastDownloadedUpdateApk = apk;
                  if (isDestroyed() || isFinishing()) return;
                  if (dialog.isShowing()) dialog.dismiss();
                  try {
                    AppUpdater.installApk(this, apk);
                  } catch (Exception e) {
                    message("Could not launch installer: " + e.getMessage());
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  updateDownloading = false;
                  if (dialog.isShowing()) dialog.dismiss();
                  if (!cancelled.get() && !isDestroyed()) {
                    message("Update failed: " + e.getMessage());
                  }
                });
          }
        });
  }

  @Override
  public boolean mfVisible() {
    return mfOn;
  }

  @Override
  public void mfSlide(float fraction) {
    if (camera == null) return;
    int step = Math.max(0, Math.min(MF_STEPS, Math.round(fraction * MF_STEPS)));
    if (step == mfStep) return;
    mfStep = step;
    camera.setFocusFraction(mfStep / (float) MF_STEPS);
    camera.update(snapshot());
    savePrefs();
    pushOsd();
  }

  @Override
  public float minFocusDistance() {
    return camera == null ? 0f : camera.minFocusDistance();
  }
}
