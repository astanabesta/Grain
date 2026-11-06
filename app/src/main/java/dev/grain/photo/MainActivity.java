package dev.grain.photo;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaActionSound;
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
import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.LutPreview;
import dev.grain.photo.develop.TuningControl;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
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
  private int drive, dispMode, viewMode, finish, uiRotation, deviceRotation, afFeedback, isoSetting;
  private long shutterNs;
  private boolean evf, powerOn = true, aeLock, isoDial, evCustom, sounds = true, showHistogram = true,
      showLevel = true, autoRotate = true, halfPressed, halfClosedOverlay, neutralHeld;
  private float afX = .5f, afY = .5f, levelSmooth = Float.NaN;
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
  private static final String[] DRIVES = {"SINGLE", "TIMER 3S", "TIMER 5S", "FS BKT"};
  private static final String[] VIEW_MODES = {"EYE SENSOR", "EVF ONLY", "LCD ONLY"};
  private static final String[] EV_LABELS = {
    "−2", "−1⅔", "−1⅓", "−1", "−⅔", "−⅓", "0", "+⅓", "+⅔", "+1", "+1⅓", "+1⅔", "+2", "C"
  };
  private static final int EV_C = 13;
  private ScrollView panelScroll;
  private GrainCamera camera;
  private OrientationEventListener orientationListener;
  /** Film-sheet thumbnails through each imported LUT, built once per import. */
  private final java.util.Map<String, Bitmap> lutPreviews = new java.util.HashMap<>();
  private PhotoStore store;
  private SharedPreferences preferences;
  private JSONObject capabilities = new JSONObject();
  private int recipe, strengths[] = new int[Recipe.ALL.length], timerSeconds;
  private LutLibrary luts;
  /** Saved per-film tuning of the RAW films. */
  private RecipeTuning tuning;
  private ActivityResultLauncher<String[]> lutPicker;
  private boolean keepRawFiles = true;
  private Runnable afterLutImport;
  private String ratio = "3:4";
  private double zoom = 1;
  private float ev = 0;
  /** The AF area mode, a {@link GrainCamera} AF_* constant; see AF_AREA_NAMES. */
  private int afArea = GrainCamera.AF_SINGLE;
  /** The AF area modes this phone can really do, in menu order. See {@link #ready}. */
  private final List<Integer> afAreas = new ArrayList<>();
  /** Manual focus: the lens is driven from a distance instead of by the autofocus. */
  private boolean mfOn;
  /** The manual-focus step; 0 is infinity, {@link #MF_STEPS} is the lens' closest focus. */
  private int mfStep = MF_STEPS / 2;
  /** Peaking: the preview's edges are drawn back over it, so focus is visible. */
  private boolean peaking;
  private boolean grid,
      keepFull = true,
      faceExposure = true,
      faceLandmarks = false,
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
  private PhotoStore.Photo pendingDelete;
  private LinearLayout snackbar;
  private Runnable purgeTask;
  private String page = "camera";

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    preferences = getSharedPreferences("grain", MODE_PRIVATE);
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
    camera.setFaceLandmarks(faceLandmarks);
    camera.setAfArea(afArea);
    camera.setAfLock(afLock);
    camera.setManualFocus(mfOn);
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
    if (r.fuji == FujiRecipe.REGGIES_SUPERIA) return "SUP";
    if (r.fuji == FujiRecipe.KODAK_GOLD_200) return "GLD";
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
        isoSetting);
  }

  private void selectRecipe(int id) {
    if (busy) return;
    recipe = id;
    savePrefs();
    updateLabels();
    camera.update(snapshot());
    Recipe r = Recipe.ALL[id];
    String sub = r.raw ? (r.standard ? "film simulation · developed from RAW" : "recipe · developed from RAW") : "phone ISP look";
    if (r.raw) {
      if (ready && !camera.rawAvailable())
        message("RAW isn’t available here: " + camera.rawUnavailableReason());
      else if (!luts.has(r.fuji.filmSimulation))
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
      boolean lutPreview = f.raw && luts.has(f.fuji.filmSimulation) && artwork != null;
      Bitmap cachedPreview = lutPreview ? lutPreviews.get(f.fuji.filmSimulation) : null;
      art.setImageBitmap(cachedPreview != null ? cachedPreview : artwork);
      if (lutPreview) {
        if (cachedPreview == null) previewThroughLut(art, f.fuji.filmSimulation, artwork);
        art.setContentDescription(
            "Reference picture through " + (f.fuji.sony() ? "Sony’s " : "Fujifilm’s ") + f.fuji.filmLabel
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
              f.standard ? f.fuji.sony() ? "SONY LOOK" : "FILM SIMULATION" : f.raw ? "RAW · RECIPE" : "ISP LOOK",
              8,
              f.raw ? ORANGE : MUTED);
      iso.setTypeface(Typeface.MONOSPACE);
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
                            : current.standard ? "Fujifilm’s standard settings" : "the published recipe"));
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

  private void previewThroughLut(ImageView art, String sim, Bitmap artwork) {
    files.execute(
        () -> {
          try {
            int w = artwork.getWidth(), h = artwork.getHeight();
            int[] px = new int[w * h];
            artwork.getPixels(px, 0, w, 0, 0, w, h);
            LutPreview.apply(px, luts.load(sim));
            Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            runOnUiThread(
                () -> {
                  lutPreviews.put(sim, out);
                  art.setColorFilter(null);
                  art.setImageBitmap(out);
                });
          } catch (Exception e) {
            android.util.Log.w("Grain", "LUT preview failed for " + sim, e);
          }
        });
  }

  /** Film Lab body for RAW recipes: published settings, LUT status and import. */
  private void fillRecipePanel(LinearLayout panel, Recipe r, Dialog dialog) {
    panel.removeAllViews();
    if (!r.raw) return;
    boolean hasLut = luts.has(r.fuji.filmSimulation);
    boolean hasRaw = !ready || camera.rawAvailable();
    boolean sony = r.fuji.sony();
    TextView state =
        text(
            !hasRaw
                ? "RAW unavailable: " + camera.rawUnavailableReason()
                : hasLut
                    ? (sony ? "Sony " : "Fujifilm ") + r.fuji.filmLabel + " LUT ready · " + luts.summary()
                    : sony
                        ? "Needs Sony’s free official s709 LUT (s709Cubes.zip, about 0.5 MB)"
                        : "Needs Fujifilm’s free official LUT pack (" + r.fuji.filmLabel + " inside)",
            10,
            hasLut && hasRaw ? INK : ORANGE);
    state.setGravity(Gravity.START);
    panel.addView(state, new LinearLayout.LayoutParams(-1, -2));
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
    FujiRecipe.Tuning work = tuning.effective(r).tuning();
    FujiRecipe.Tuning defaults = r.fuji.tuning();
    LinearLayout head = row();
    head.setPadding(0, dp(4), 0, dp(2));
    TextView heading = text("SETTINGS", 9, MUTED);
    heading.setTypeface(Typeface.MONOSPACE);
    heading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    head.addView(heading, new LinearLayout.LayoutParams(0, dp(24), 1));
    TextView badge = text("", 9, MUTED);
    badge.setTypeface(Typeface.MONOSPACE);
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
    for (TuningControl setting : TuningControl.fujifilm(r.fuji))
      rows.add(stepper(panel, setting, work, defaults, changed));
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
    extrasTitle.setTypeface(Typeface.MONOSPACE);
    extrasTitle.setGravity(Gravity.START);
    extrasTitle.setPadding(0, dp(14), 0, dp(4));
    panel.addView(extrasTitle, new LinearLayout.LayoutParams(-1, -2));
    for (TuningControl setting : TuningControl.extras())
      rows.add(stepper(panel, setting, work, defaults, changed));
    TextView extrasNote =
        paragraph(
            "Development: First version develops like Grain v0.2.0 — colour noise reduction"
                + " only, no sharpening at Sharpness −2 and the full grain, so the picture is"
                + " grittier and more like film. Raising High ISO NR adds luminance noise"
                + " reduction and Sharpness above −2 sharpens. Clean (v0.2.1 and later) removes"
                + " more noise and sharpens. Reggie’s Portra and Reggie’s Superia start on First"
                + " version, every other film on Clean.\n"
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
                : "Tuning changes the developed photo; the viewfinder is the phone’s preview, with the"
                    + " nearest white-balance preset when the film uses a fixed white balance. Each"
                    + " photo is developed from the sensor RAW with Fujifilm’s LUT plus Grain’s stills"
                    + " tone model. Fixed white balances use Fujifilm’s preset white points measured"
                    + " from Fujifilm RAW files. Dynamic range and D-Range Priority, WB shift,"
                    + " Clarity, Grain, Color Chrome, FX Blue, the Acros filters and the extras are"
                    + " Grain’s approximations.");
    panel.addView(note);
  }

  /** Gallery: the LUT a photo was developed with ("SONY-S709" reads as Sony s709). */
  private static String lutLabel(String lut) {
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
        "A little help finding your frame.",
        grid,
        v -> {
          grid = v;
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
              drive = 0;
              shutterNs = 0;
              isoSetting = 0;
              evCustom = false;
              aeLock = false;
              camera.setAeLock(false);
              grid = false;
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
    if (!luts.has(r.fuji.filmSimulation)) {
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
    status.setText("Tap shutter again to cancel");
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
    camera.neutralPreview(false);
    neutralHeld = false;
    Recipe r = Recipe.ALL[recipe];
    bracketFilms = drive == 3 && r.raw ? bracketSet() : null;
    if (drive == 3 && !r.raw) back.toast("FS BKT", "bracketing develops RAW films · single frame");
    back.state.countdown = "";
    shutter.setText("·");
    shutter.setContentDescription("Capturing photo");
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
      if (f.raw && f.id != recipe && luts.has(f.fuji.filmSimulation)) out.add(f);
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
    saving = false;
    busy = false;
    shutter.setText("◉");
    shutter.setContentDescription("Take photo");
    status.setText("Camera needs attention · shutter reconnects");
    ready = false;
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
    status.setText("Saving your moment…");
    files.execute(
        () -> {
          try {
            PhotoStore.Photo photo =
                store.save(jpeg, settings.ratio, settings.zoom, settings.keepFull, receipt);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
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
    status.setText(
        bracket.length > 1
            ? "Developing " + bracket.length + " films from one RAW…"
            : "Developing " + filmName + " from RAW…");
    files.execute(
        () -> {
          try {
            long t = System.nanoTime();
            PhotoStore.Photo photo = null;
            for (int i = 0; i < bracket.length; i++) {
              FujiRecipe f = bracketTuning[i];
              boolean fTuned = !f.sameSettings(bracket[i].fuji);
              CubeLut lut = luts.load(f.filmSimulation);
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
                      lut,
                      developEv,
                      capture.frame.exposureNs ^ System.nanoTime(),
                      meterFaces);
              frameReceipt.put(
                  "development", out.stats.put("totalMs", (System.nanoTime() - start) / 1_000_000));
              PhotoStore.Photo saved =
                  store.save(
                      out.jpeg,
                      settings.ratio,
                      settings.zoom,
                      settings.keepFull,
                      frameReceipt,
                      i == 0 ? capture.dng : null,
                      i == 0 && keepRawFiles ? capture.phoneJpeg : null);
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
                  saving = false;
                  busy = false;
                  shutter.setText("◉");
                  shutter.setContentDescription("Take photo");
                  status.setText(
                      first.metadata.optInt("width") + " × " + first.metadata.optInt("height")
                          + " · " + filmName + " · developed in " + String.format(Locale.US, "%.1f s", ms / 1000.0));
                  updateThumb();
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
                  saving = false;
                  busy = false;
                  shutter.setText("◉");
                  shutter.setContentDescription("Take photo");
                  status.setText("RAW development failed · camera still ready");
                  message("RAW development failed: " + why);
                });
          }
        });
  }

  private void openCamera() {
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
      if (result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED) openCamera();
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

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    immersive();
    if (orientationListener != null && orientationListener.canDetectOrientation())
      orientationListener.enable();
    if (texture != null && texture.isAvailable()) openCamera();
    readBattery();
    main.removeCallbacks(histogramTask);
    main.postDelayed(histogramTask, 500);
    if (peaking) startPeaking();
    updateThumb();
  }

  @Override
  protected void onPause() {
    if (orientationListener != null) orientationListener.disable();
    main.removeCallbacks(histogramTask);
    main.removeCallbacks(peakingTask);
    if (halfPressed) releaseHalf();
    neutralHeld = false;
    dismissCameraTool();
    resumed = false;
    cancelTimer();
    if (camera != null) {
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
    if (camera != null) camera.close();
    if (sound != null) sound.release();
    main.removeCallbacksAndMessages(null);
    files.shutdown();
    super.onDestroy();
  }

  @Override
  public boolean onKeyDown(int code, KeyEvent event) {
    if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
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
    if ("camera".equals(page) && evf && viewMode != 1) {
      setEvf(false);
      return;
    }
    if (counting) cancelTimer();
    else if ("detail".equals(page)) showGallery();
    else if (!"camera".equals(page)) closePanel();
    else finish();
  }

  private void updateThumb() {
    files.execute(
        () -> {
          int count = store.list().size();
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
  }

  private void closePanel() {
    if (panel != null) {
      root.removeView(panel);
      panel = null;
    }
    page = "camera";
  }

  private void showGallery() {
    if (busy) {
      message("Still saving your moment…");
      return;
    }
    openPanel("The moments you kept.", "NEWEST FIRST · PRIVATE ON THIS DEVICE");
    LinearLayout body = column();
    body.setPadding(dp(17), dp(12), dp(17), dp(20));
    panelScroll.addView(body);
    java.util.List<PhotoStore.Photo> photos = store.list();
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
    } else {
      for (int i = 0; i < photos.size(); i += 2) {
        LinearLayout row = row();
        row.setGravity(Gravity.TOP);
        body.addView(row);
        for (int j = i; j < Math.min(i + 2, photos.size()); j++) {
          PhotoStore.Photo photo = photos.get(j);
          LinearLayout card = column();
          card.setPadding(dp(5), dp(5), dp(5), dp(7));
          card.setBackground(bg(0xfffffcf4, 5, LINE, 1));
          LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
          cp.setMargins(dp(4), dp(4), dp(4), dp(4));
          row.addView(card, cp);
          ImageView image = new ImageView(this);
          image.setScaleType(ImageView.ScaleType.CENTER_CROP);
          image.setContentDescription("Open captured photo");
          card.addView(image, new LinearLayout.LayoutParams(-1, dp(180)));
          loadImage(image, photo.image, 400);
          JSONObject r = photo.metadata.optJSONObject("recipe");
          card.addView(
              text(
                  (r == null ? "Photo" : r.optString("name"))
                      + " · "
                      + photo.metadata.optString("ratio"),
                  10,
                  MUTED),
              new LinearLayout.LayoutParams(-1, dp(29)));
          card.setOnClickListener(v -> showPhoto(photo));
        }
        if (i + 1 == photos.size())
          row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
      }
    }
    body.addView(
        paragraph(
            "Stored privately. Export photos you want in the system gallery. Uninstalling Grain"
                + " deletes its private photos."));
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
    store.purgeTrash();
    if (!store.trash(photo)) {
      message("Could not delete this photo");
      return;
    }
    pendingDelete = photo;
    showGallery();
    updateThumb();
    if (purgeTask != null) main.removeCallbacks(purgeTask);
    showSnackbar(
        "Photo deleted.",
        "Undo",
        () -> {
          if (pendingDelete != null && store.restore(pendingDelete)) {
            pendingDelete = null;
            main.removeCallbacks(purgeTask);
            showGallery();
            updateThumb();
            message("Photo restored.");
          }
        });
    purgeTask =
        () -> {
          pendingDelete = null;
          store.purgeTrash();
        };
    main.postDelayed(purgeTask, 7000);
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
    showJson("Camera diagnostics", capabilities);
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
    ScrollView scroll = new ScrollView(this);
    TextView text = text(json.toString(), 10, INK);
    try {
      text.setText(json.toString(2).replace("\\/", "/"));
    } catch (Exception ignored) {
    }
    text.setTypeface(Typeface.MONOSPACE);
    text.setGravity(Gravity.TOP | Gravity.START);
    text.setPadding(dp(16), dp(12), dp(16), dp(12));
    text.setTextIsSelectable(true);
    scroll.addView(text);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(scroll)
        .setPositiveButton("Close", null)
        .setNeutralButton(
            "Copy",
            (d, w) -> {
              android.content.ClipboardManager clip =
                  (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
              String plain;
              try {
                plain = json.toString(2).replace("\\/", "/");
              } catch (Exception e) {
                plain = json.toString();
              }
              clip.setPrimaryClip(ClipData.newPlainText(title, plain));
              message("Diagnostics copied");
            })
        .show();
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
    grid = preferences.getBoolean("grid", false);
    keepFull = preferences.getBoolean("keepFull", true);
    larger = preferences.getBoolean("larger", false);
    faceExposure = preferences.getBoolean("faceExposure", true);
    faceLandmarks = preferences.getBoolean("faceLandmarks", false);
    afArea = Math.max(0, Math.min(4, preferences.getInt("afArea", GrainCamera.AF_SINGLE)));
    afLock = preferences.getBoolean("afLock", false);
    mfOn = preferences.getBoolean("mfOn", false);
    mfStep = Math.max(0, Math.min(MF_STEPS, preferences.getInt("mfStep", MF_STEPS / 2)));
    peaking = preferences.getBoolean("peaking", false);
    timerSeconds = preferences.getInt("timer", 0);
    if (timerSeconds != 3 && timerSeconds != 5) timerSeconds = 0;
    drive = preferences.getInt("drive", timerSeconds == 3 ? 1 : timerSeconds == 5 ? 2 : 0);
    if (drive < 0 || drive >= DRIVES.length) drive = 0;
    timerSeconds = drive == 1 ? 3 : drive == 2 ? 5 : 0;
    dispMode = Math.max(0, Math.min(1, preferences.getInt("disp", 0)));
    viewMode = Math.max(0, Math.min(2, preferences.getInt("viewMode", 0)));
    finish = Math.max(0, Math.min(2, preferences.getInt("finish", 0)));
    sounds = preferences.getBoolean("sounds", true);
    showHistogram = preferences.getBoolean("histogram", true);
    showLevel = preferences.getBoolean("level", true);
    autoRotate = preferences.getBoolean("autoRotate", true);
    isoDial = preferences.getBoolean("isoDial", false);
  }

  private void savePrefs() {
    SharedPreferences.Editor edit =
        preferences
            .edit()
            .putInt("recipe", recipe)
            .putString("ratio", ratio)
            .putBoolean("grid", grid)
            .putBoolean("keepFull", keepFull)
            .putBoolean("larger", larger)
            .putBoolean("faceExposure", faceExposure)
            .putBoolean("faceLandmarks", faceLandmarks)
            .putInt("afArea", afArea)
            .putBoolean("afLock", afLock)
            .putBoolean("mfOn", mfOn)
            .putInt("mfStep", mfStep)
            .putBoolean("peaking", peaking)
            .putBoolean("keepRawFiles", keepRawFiles)
            .putInt("timer", timerSeconds)
            .putInt("drive", drive)
            .putInt("disp", dispMode)
            .putInt("viewMode", viewMode)
            .putInt("finish", finish)
            .putBoolean("sounds", sounds)
            .putBoolean("histogram", showHistogram)
            .putBoolean("level", showLevel)
            .putBoolean("autoRotate", autoRotate)
            .putBoolean("isoDial", isoDial);
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
    double t = shutterNs, iso = isoSetting, shown = ev, warn = 0;
    if (m != null && m.exposureNs > 0 && m.iso > 0) {
      double neutral = m.neutralNsIso(), target = neutral * Math.pow(2, ev);
      org.json.JSONArray times = capabilities.optJSONArray("exposureTimeRangeNs");
      org.json.JSONArray isos = capabilities.optJSONArray("isoRange");
      double tMin = times == null ? 1 : times.optLong(0), tMax = times == null ? 1e12 : times.optLong(1);
      double iMin = isos == null ? 1 : isos.optInt(0), iMax = isos == null ? 1e6 : isos.optInt(1);
      if (shutterNs > 0 && isoSetting > 0) shown = Math.log(t * iso / neutral) / Math.log(2);
      else if (shutterNs > 0) {
        double want = target / t;
        iso = Math.max(iMin, Math.min(iMax, want));
        if (want < iMin * .97 || want > iMax * 1.03) warn = 1;
        shown = Math.log(t * iso / neutral) / Math.log(2);
      } else if (isoSetting > 0) {
        double want = target / iso;
        t = Math.max(tMin, Math.min(tMax, want));
        if (want < tMin * .97 || want > tMax * 1.03) warn = 1;
        shown = Math.log(t * iso / neutral) / Math.log(2);
      } else {
        t = m.exposureNs;
        iso = m.iso;
      }
    }
    return new double[] {t, iso, shown, warn};
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
    double[] plan = exposurePlan();
    o.mode = shutterNs > 0 && isoSetting > 0 ? "M" : shutterNs > 0 ? "S" : "P";
    o.shutter = shutterText((long) plan[0], false);
    double aperture = capabilities.optDouble("aperture", 0);
    o.aperture = aperture > 0 ? String.format(Locale.US, "F%.1f", aperture) : "";
    o.iso = plan[1] > 0 ? String.valueOf(Math.round(plan[1])) : "";
    o.isoAuto = isoSetting == 0;
    o.manual = shutterNs > 0 || isoSetting > 0;
    o.exposure = plan[2];
    o.exposureWarn = plan[3] > 0;
    o.drive = drive == 0 ? "" : drive == 3 ? "FS BKT" : "◷ " + timerSeconds + "S";
    o.zoom = zoom > 1.04 ? String.format(Locale.US, "%.1f×", zoom) : "";
    o.aeLock = aeLock;
    o.grid = grid;
    o.showHistogram = showHistogram;
    o.showLevel = showLevel;
    o.infoOff = dispMode == 1;
    o.histogram = showHistogram && ready ? histogramBins : null;
    o.afX = afX;
    o.afY = afY;
    o.afArea = afArea;
    o.afState = afFeedback;
    o.busy = busy;
    back.refresh();
  }

  /** Electronic level, smoothed; NaN hides it (phone flat). */
  private void level(float tilt) {
    if (back == null) return;
    if (Float.isNaN(tilt)) {
      levelSmooth = Float.NaN;
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
          if (!showHistogram || !ready || !powerOn || back == null || !"camera".equals(mode)
              || dispMode == 1 || texture == null || !texture.isAvailable())
            return;
          if (histogramBitmap == null) {
            histogramBitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888);
            histogramPixels = new int[48 * 64];
          }
          Bitmap b = texture.getBitmap(histogramBitmap);
          if (b == null) return;
          b.getPixels(histogramPixels, 0, 48, 0, 0, 48, 64);
          Arrays.fill(histogramBins, 0);
          for (int c : histogramPixels) {
            int luma = (((c >> 16) & 255) * 54 + ((c >> 8) & 255) * 183 + (c & 255) * 19) >> 8;
            histogramBins[Math.min(63, luma >> 2)]++;
          }
          back.state.histogram = histogramBins;
          back.refresh();
        }
      };

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
          if (peakBitmap == null) {
            peakBitmap = Bitmap.createBitmap(PEAK_W, PEAK_H, Bitmap.Config.ARGB_8888);
            peakPixels = new int[PEAK_W * PEAK_H];
            peakLuma = new int[PEAK_W * PEAK_H];
            peakOut = new int[PEAK_W * PEAK_H];
          }
          Bitmap b = texture.getBitmap(peakBitmap);
          if (b == null) return;
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
        }
      };

  @Override
  public void meter(GrainCamera.Meter m) {
    lastMeter = m;
    if (halfPressed && afFeedback == 1) {
      if (m.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
        afFeedback = 2;
        back.lamp.set(1);
        if (sounds && sound != null) sound.play(MediaActionSound.FOCUS_COMPLETE);
      } else if (m.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
        afFeedback = 3;
        back.lamp.set(3);
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
    double sx = mapped[0], sy = mapped[1];
    if (rotation == 90) {
      sx = mapped[1];
      sy = 1 - mapped[0];
    } else if (rotation == 270) {
      sx = 1 - mapped[1];
      sy = mapped[0];
    } else if (rotation == 180) {
      sx = 1 - sx;
      sy = 1 - sy;
    }
    camera.focus(sx, sy, hold);
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
        if (viewMode == 2) back.toast("LCD ONLY", "VIEW MODE → EYE SENSOR to use the viewfinder");
        else setEvf(!evf);
        break;
      case CameraBack.K_VIEW_MODE:
        viewMode = (viewMode + 1) % VIEW_MODES.length;
        savePrefs();
        applyViewMode(true);
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
    camera.update(snapshot());
    pushOsd();
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
      afFeedback = 1;
      focusAt(afX, afY, true);
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
    timerSeconds = drive == 1 ? 3 : drive == 2 ? 5 : 0;
    savePrefs();
    pushOsd();
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
        drive == 3 ? "one RAW, developed as three films" : drive == 0 ? "one frame per press" : "self-timer · tap the shutter to cancel");
  }

  private void toggleAeLock() {
    aeLock = !aeLock;
    camera.setAeLock(aeLock);
    camera.update(snapshot());
    pushOsd();
    back.toast(aeLock ? "AE-L" : "AE UNLOCKED", aeLock ? "exposure held until you press again" : null);
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

  private void commandKey(int steps) {
    switch (mode) {
      case "q":
        stepQ(steps);
        break;
      case "menu":
        stepMenu(steps);
        break;
      case "play":
        browse(steps);
        break;
      default:
        if (evCustom) {
          ev = Math.max(-2, Math.min(2, Math.round((ev + steps * .1f) * 10) / 10f));
          camera.update(snapshot());
          pushOsd();
          back.toast(String.format(Locale.US, "%+.1f EV", ev), null);
        } else setZoom(Math.round((zoom + steps * .1) * 10) / 10.0);
        break;
    }
  }

  private void setEvf(boolean on) {
    if (on == evf || back == null) return;
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
    qTiles.clear();
    menuList = null;
    menuTabs = null;
    menuScroll = null;
    playImage = null;
    playInfo = null;
    playReceipt = null;
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

  /** The tuning control behind a Q tile, or null when this film has no such setting. */
  private TuningControl qControl(Recipe r, int index) {
    if (!r.raw) return null;
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
    if (!r.raw && index == 1) return "STRENGTH";
    if (r.raw && r.fuji.monochrome() && index == 7) return "FILTER";
    return Q_ITEMS[index][0];
  }

  private String qFullName(Recipe r, int index) {
    String key = Q_ITEMS[index][1];
    if ("film".equals(key)) return "FILM SIMULATION · " + r.name.toUpperCase(Locale.ROOT);
    if ("face".equals(key)) return "FACE EXPOSURE";
    if (!r.raw) return index == 1 ? "LOOK STRENGTH" : "NOT FOR ISP LOOKS";
    TuningControl c = qControl(r, index);
    return c == null ? "NOT FOR THIS FILM" : c.name.toUpperCase(Locale.ROOT);
  }

  private String qValueText(Recipe r, int index, FujiRecipe.Tuning t) {
    String key = Q_ITEMS[index][1];
    if ("film".equals(key)) return filmCode(r);
    if ("face".equals(key)) return r.raw ? onOff(faceExposure) : "—";
    if (!r.raw) return index == 1 ? strengths[recipe] + "%" : "—";
    TuningControl c = qControl(r, index);
    if (c == null) return "—";
    if (!c.enabled(t)) return c.disabledLabel == null ? "—" : shortLabel(c.disabledLabel);
    return shortLabel(c.labels[c.at(t)]);
  }

  private void openQ() {
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
      boolean usable =
          i == 0 || (i == 15 && r.raw) || (!r.raw && i == 1) || (c != null && t != null && c.enabled(t));
      boolean changed = c != null && t != null && c.enabled(t) && c.at(t) != c.at(defaults);
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
  private static final int MF_STEPS = 30;
  /** The peaking read-back, kept small so measuring it does not cost the preview. */
  private static final int PEAK_W = 160, PEAK_H = 120;
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
                () -> mfOn ? "MANUAL" : "AUTO",
                d -> {
                  mfOn = !mfOn;
                  camera.setManualFocus(mfOn);
                  camera.update(snapshot());
                  savePrefs();
                  pushOsd();
                },
                false));
        items.add(
            new Item(
                "MF DISTANCE",
                () -> mfOn ? (mfStep == 0 ? "INFINITY" : "STEP " + mfStep + " / " + MF_STEPS) : "AUTO",
                d -> {
                  if (!mfOn) return;
                  mfStep = Math.max(0, Math.min(MF_STEPS, mfStep + (d < 0 ? -1 : 1)));
                  camera.setFocusFraction(mfStep / (float) MF_STEPS);
                  camera.update(snapshot());
                  savePrefs();
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
        items.add(new Item("DRIVE", () -> DRIVES[drive], d -> setDrive(drive + (d < 0 ? -1 : 1)), false));
        items.add(new Item("SHUTTER / ISO", () -> shutterNs > 0 || isoSetting > 0
            ? (shutterNs > 0 ? shutterText(shutterNs, false) : "A") + " · " + (isoSetting > 0 ? "ISO " + isoSetting : "ISO A")
            : "AUTO", d -> {
          shutterNs = 0;
          isoSetting = 0;
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
        items.add(new Item("FRAMING GRID", () -> onOff(grid), d -> {
          grid = !grid;
          savePrefs();
          pushOsd();
        }, false));
        items.add(new Item("BODY FINISH", () -> CameraBack.FINISHES[finish], d -> {
          finish = (finish + (d < 0 ? 2 : 1)) % 3;
          back.setFinish(finish);
          savePrefs();
        }, false));
        items.add(new Item("ALL PREFERENCES", () -> "›", d -> {
          closeOverlays();
          showSettings();
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
                mode = "play";
                back.showOverlay(buildPlayback());
                showPlayed();
              });
        });
  }

  private View buildPlayback() {
    FrameLayout box = new FrameLayout(this);
    box.setBackgroundColor(0xff000000);
    playImage = new ImageView(this);
    playImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
    box.addView(playImage, new FrameLayout.LayoutParams(-1, -1));
    playInfo = text("", 8.5f, 0xfff4f1e8);
    playInfo.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    playInfo.setPadding(dp(8), 0, dp(8), 0);
    playInfo.setBackgroundColor(0x99000000);
    playInfo.setSingleLine(true);
    playInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
    box.addView(playInfo, new FrameLayout.LayoutParams(-1, dp(24), Gravity.TOP));
    TextView hint = text("◀ ▶ BROWSE · DISP RECEIPT · DRIVE DELETE · OK DETAILS · Q ALL", 6.5f, 0xffc9c4b8);
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
    GestureDetector gestures =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy)) {
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
                okKey();
                return true;
              }
            });
    box.setOnTouchListener(
        (v, e) -> {
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
    playImage.setImageDrawable(null);
    loadImage(playImage, p.image, 1600);
    JSONObject r = p.metadata.optJSONObject("recipe");
    playInfo.setText(
        (playIndex + 1) + " / " + playList.size() + "    "
            + (r == null ? "PHOTO" : r.optString("name").toUpperCase(Locale.ROOT))
            + (p.metadata.has("bracket") ? "  ·  FS BKT " + p.metadata.optString("bracket") : "")
            + (p.dng != null ? "  ·  DNG" : ""));
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
    receiptLine(s, "FILM", r == null ? "" : r.optString("name"));
    if (a != null)
      receiptLine(s, "EXPOSURE", shutterText(a.optLong("exposureNs"), false) + "  ·  ISO " + a.optInt("iso"));
    JSONObject manual = q == null ? null : q.optJSONObject("manual");
    if (manual != null) receiptLine(s, "MANUAL", manual.optString("mode"));
    if (q != null && q.optBoolean("aeLock")) receiptLine(s, "AE-L", "locked");
    if (d != null) {
      if (d.has("developmentGainEv"))
        receiptLine(s, "GAIN", String.format(Locale.US, "%+.2f EV", d.optDouble("developmentGainEv")));
      String face = d.optString("faceExposure", "");
      if (!face.isEmpty()) receiptLine(s, "FACE", face.length() > 48 ? face.substring(0, 48) + "…" : face);
      if (d.has("totalMs")) receiptLine(s, "DEVELOPED", String.format(Locale.US, "%.1f s", d.optLong("totalMs") / 1000.0));
    }
    receiptLine(s, "FS BKT", m.optString("bracket", ""));
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
}
