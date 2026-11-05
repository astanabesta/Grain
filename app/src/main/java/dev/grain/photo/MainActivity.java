package dev.grain.photo;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Grain's single-camera-screen interaction design, implemented without Flutter or a WebView. */
public final class MainActivity extends ComponentActivity implements GrainCamera.Listener {
  private static final int PAPER = 0xfff4f1e8,
      INK = 0xff303c29,
      MUTED = 0xff87917c,
      ORANGE = 0xffe76736,
      LINE = 0xffd6ddca;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private FrameLayout root, stage, frame, cameraToolLayer;
  private String cameraTool;
  private SeekBar activeZoomRange;
  private TextView activeZoomValue, activeZoomNote;
  private LinearLayout cameraPage, panel;
  private TextureView texture;
  private GridOverlay overlay;
  private TextView filmLabel,
      ratioButton,
      zoomButton,
      exposureButton,
      timerButton,
      tuneButton,
      status,
      shutter,
      photoButton,
      photoBadge;
  private LinearLayout filmRow;
  private ScrollView panelScroll;
  private GrainCamera camera;
  private PhotoStore store;
  private SharedPreferences preferences;
  private JSONObject capabilities = new JSONObject();
  private int recipe, strengths[] = {80, 80, 80, 80}, timerSeconds;
  private String ratio = "3:4";
  private double zoom = 1;
  private float ev = 0;
  private boolean grid,
      keepFull = true,
      larger,
      front,
      busy,
      saving,
      ready,
      resumed,
      permissionAsked;
  private int sourceWidth = 3060, sourceHeight = 4080;
  private long blockFocusUntil;
  private int countdownToken;
  private boolean counting;
  private ScaleGestureDetector scaleDetector;
  private PhotoStore.Photo pendingDelete;
  private LinearLayout snackbar;
  private Runnable purgeTask;
  private String page = "camera";

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    preferences = getSharedPreferences("grain", MODE_PRIVATE);
    loadPrefs();
    store = new PhotoStore(this);
    store.purgeTrash();
    build();
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() { navigateBack(); }
    });
    camera = new GrainCamera(this, texture, this);
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
    root.setBackgroundColor(PAPER);
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(i.left, i.top, i.right, i.bottom);
          } else
            root.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    root.requestApplyInsets();
    cameraPage = column();
    root.addView(cameraPage, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout header = row();
    header.setPadding(dp(20), dp(6), dp(17), dp(6));
    cameraPage.addView(header, new LinearLayout.LayoutParams(-1, dp(62)));
    TextView logo = text("grain.", 31, INK);
    android.text.SpannableString brand = new android.text.SpannableString("grain.");
    brand.setSpan(new android.text.style.ForegroundColorSpan(ORANGE), 5, 6, 0);
    logo.setText(brand);
    logo.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    logo.setTypeface(Typeface.create("serif", Typeface.BOLD));
    header.addView(logo, new LinearLayout.LayoutParams(0, -1, 1));
    timerButton =
        button(
            "◷",
            19,
            () -> {
              if (counting) {
                cancelTimer();
                return;
              }
              timerSeconds = timerSeconds == 0 ? 3 : timerSeconds == 3 ? 5 : 0;
              savePrefs();
              updateLabels();
              message(
                  timerSeconds == 0
                      ? "Timer off"
                      : timerSeconds + " second timer · tap shutter again to cancel");
            });
    timerButton.setContentDescription("Cycle self timer: off, three, five seconds");
    header.addView(timerButton, size(38, 40));
    TextView settings = iconButton("settings", INK, 21, this::showSettings);
    settings.setContentDescription("Camera preferences");
    header.addView(settings, size(40, 43));
    stage = new FrameLayout(this);
    stage.setBackground(bg(0xff20281e, 6, 0, 0));
    LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1);
    sl.setMargins(dp(11), 0, dp(11), 0);
    cameraPage.addView(stage, sl);
    stage.setClipChildren(true);
    stage.setClipToOutline(true);
    frame = new FrameLayout(this);
    frame.setClipChildren(true);
    stage.addView(frame, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    texture = new TextureView(this);
    frame.addView(texture, new FrameLayout.LayoutParams(-1, -1));
    overlay = new GridOverlay();
    frame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout top = row();
    top.setPadding(dp(12), dp(12), dp(12), 0);
    stage.addView(top, new FrameLayout.LayoutParams(-1, dp(46), Gravity.TOP));
    filmLabel = text("DAYLIGHT / ISP", 8, PAPER);
    filmLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    filmLabel.setLetterSpacing(.12f);
    filmLabel.setBackground(bg(0x88303c29, 18, 0, 0));
    filmLabel.setPadding(dp(9), 0, dp(9), 0);
    top.addView(filmLabel, new LinearLayout.LayoutParams(-2, dp(25)));
    top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    TextView gridButton =
        tool(
            "▦",
            () -> {
              grid = !grid;
              savePrefs();
              overlay.invalidate();
            });
    GrainStyle.icon(gridButton, "thirds", PAPER, 15);
    gridButton.setPadding(dp(9), 0, 0, 0);
    gridButton.setContentDescription("Toggle composition grid");
    LinearLayout.LayoutParams gp = size(33, 30);
    gp.setMargins(dp(8), 0, dp(6), 0);
    top.addView(gridButton, gp);
    ratioButton = tool("3:4⌄", this::showRatio);
    ratioButton.setContentDescription("Choose photo aspect ratio");
    top.addView(ratioButton, size(52, 30));
    LinearLayout bottom = row();
    bottom.setPadding(dp(12), 0, dp(12), dp(12));
    stage.addView(bottom, new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM));
    exposureButton = tool("☀ 0.0", this::showExposure);
    exposureButton.setContentDescription("Adjust exposure compensation");
    bottom.addView(exposureButton, size(65, 30));
    View spacer = new View(this);
    bottom.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
    TextView one = tool("1×", () -> setZoom(1));
    one.setContentDescription("One times digital zoom");
    one.setOnLongClickListener(
        v -> {
          showZoom();
          return true;
        });
    LinearLayout zoomPill = row();
    zoomPill.setPadding(dp(4), dp(4), dp(4), dp(4));
    zoomPill.setBackground(bg(0x99575e4e, 30, 0x44ffffff, 1));
    one.setBackground(null);
    zoomPill.addView(one, size(28, 27));
    zoomButton = tool("1.0×⌄", this::showZoom);
    zoomButton.setContentDescription("Adjust digital zoom");
    zoomButton.setBackground(bg(PAPER, 20, 0, 0));
    zoomButton.setTextColor(INK);
    zoomButton.setTypeface(Typeface.MONOSPACE);
    zoomPill.addView(zoomButton, size(49, 27));
    TextView two = tool("2×", () -> setZoom(2));
    two.setContentDescription("Two times digital zoom");
    two.setOnLongClickListener(
        v -> {
          showZoom();
          return true;
        });
    two.setBackground(null);
    zoomPill.addView(two, size(28, 27));
    bottom.addView(zoomPill, new LinearLayout.LayoutParams(-2, -2));
    stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutFrame());
    scaleDetector =
        new ScaleGestureDetector(
            this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScaleBegin(ScaleGestureDetector d) {
                blockFocusUntil = SystemClock.uptimeMillis() + 800;
                return !busy;
              }

              @Override
              public boolean onScale(ScaleGestureDetector d) {
                setZoom(zoom * d.getScaleFactor());
                blockFocusUntil = SystemClock.uptimeMillis() + 800;
                return true;
              }
            });
    frame.setOnTouchListener(
        new View.OnTouchListener() {
          float x, y;

          @Override
          public boolean onTouch(View v, android.view.MotionEvent e) {
            scaleDetector.onTouchEvent(e);
            if (e.getPointerCount() > 1) blockFocusUntil = SystemClock.uptimeMillis() + 800;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
              x = e.getX();
              y = e.getY();
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                && !scaleDetector.isInProgress()
                && SystemClock.uptimeMillis() > blockFocusUntil
                && Math.hypot(e.getX() - x, e.getY() - y) < dp(16)
                && !busy) {
              double px = e.getX() / frame.getWidth(), py = e.getY() / frame.getHeight();
              double[] mapped = Framing.sourcePoint(px, py, sourceWidth, sourceHeight, ratio, zoom);
              int rotation = 0;
              try {
                rotation = capabilities.optInt("sensorOrientation", 90);
              } catch (Exception ignored) {
              }
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
              camera.focus(sx, sy);
              overlay.focus(e.getX(), e.getY());
              v.performClick();
            }
            return true;
          }
        });
    HorizontalScrollView strip = new HorizontalScrollView(this);
    strip.setHorizontalScrollBarEnabled(false);
    strip.setFillViewport(true);
    filmRow = row();
    filmRow.setPadding(dp(10), 0, dp(10), 0);
    strip.addView(filmRow);
    cameraPage.addView(strip, new LinearLayout.LayoutParams(-1, dp(53)));
    for (Recipe f : Recipe.ALL) {
      TextView chip = button("•  " + f.name, 10, () -> selectRecipe(f.id));
      chip.setMinHeight(0);
      chip.setTag(f.id);
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(30), 1);
      cp.setMargins(dp(3), 0, dp(3), 0);
      filmRow.addView(chip, cp);
    }
    LinearLayout capture = row();
    capture.setPadding(dp(28), 0, dp(28), 0);
    cameraPage.addView(capture, new LinearLayout.LayoutParams(-1, dp(94)));
    LinearLayout photos = column();
    photos.setGravity(Gravity.CENTER);
    photoButton = iconButton("grid", MUTED, 20, this::showGallery);
    photoButton.setRotation(-4);
    photoButton.setContentDescription("Open your photos");
    photoButton.setBackground(bg(0xffe7ebde, 6, LINE, 1));
    FrameLayout thumbFrame = new FrameLayout(this);
    thumbFrame.setClipChildren(false);
    thumbFrame.addView(photoButton, new FrameLayout.LayoutParams(dp(43), dp(43), Gravity.CENTER));
    photoBadge = text("", 7, PAPER);
    photoBadge.setBackground(bg(INK, 30, PAPER, 2));
    FrameLayout.LayoutParams countLp =
        new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM | Gravity.RIGHT);
    countLp.setMargins(0, 0, -dp(5), -dp(4));
    thumbFrame.addView(photoBadge, countLp);
    photoBadge.setVisibility(View.GONE);
    photos.addView(thumbFrame, size(43, 43));
    TextView photoLabel = text("Photos", 9, MUTED);
    photos.addView(photoLabel, size(55, 23));
    capture.addView(photos, size(58, 78));
    capture.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    shutter = new GrainStyle.Shutter(this);
    shutter.setText("◉");
    shutter.setOnClickListener(v -> takePhoto());
    shutter.setTextColor(PAPER);
    shutter.setBackground(null);
    shutter.setContentDescription("Take photo");
    capture.addView(shutter, size(77, 77));
    capture.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    LinearLayout flips = column();
    flips.setGravity(Gravity.CENTER);
    TextView flip =
        button(
            "⇄",
            27,
            () -> {
              if (busy) return;
              cancelTimer();
              front = !front;
              ready = false;
              camera.close();
              main.postDelayed(this::openCamera, 350);
            });
    GrainStyle.icon(flip, "flip", INK, 24);
    flip.setPadding(dp(9), 0, 0, 0);
    flip.setContentDescription("Switch front and rear cameras");
    flips.addView(flip, size(43, 43));
    flips.addView(text("Flip", 9, MUTED), size(55, 23));
    capture.addView(flips, size(58, 78));
    LinearLayout footer = row();
    footer.setPadding(dp(20), 0, dp(20), dp(8));
    cameraPage.addView(footer, new LinearLayout.LayoutParams(-1, dp(42)));
    TextView neutral = button("Hold neutral", 9, () -> {});
    neutral.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    GrainStyle.leading(neutral, "compare", MUTED, 14);
    neutral.setTextColor(MUTED);
    neutral.setContentDescription("Hold to preview the phone's neutral rendering");
    neutral.setOnTouchListener(
        (v, e) -> {
          if (busy) return false;
          if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            camera.neutralPreview(true);
            filmLabel.setText("PHONE AUTO · PREVIEW ONLY");
            return true;
          }
          if (e.getActionMasked() == MotionEvent.ACTION_UP
              || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            camera.neutralPreview(false);
            updateLabels();
            v.performClick();
            return true;
          }
          return true;
        });
    footer.addView(neutral, new LinearLayout.LayoutParams(0, -1, 1));
    tuneButton = button("Tune film 80%", 9, this::showFilmLab);
    GrainStyle.leading(tuneButton, "settings", INK, 13);
    tuneButton.setPadding(dp(11), 0, dp(8), 0);
    tuneButton.setMinHeight(0);
    tuneButton.setBackground(bg(PAPER, 30, LINE, 1));
    footer.addView(tuneButton, size(132, 31));
    status = text("Camera2 · capture-time recipes · JPEG", 8, MUTED);
    status.setVisibility(View.GONE);
    cameraPage.addView(status, new LinearLayout.LayoutParams(-1, 0));
    cameraPage.setPadding(0, 0, 0, dp(12));
  }

  private void layoutFrame() {
    if (stage.getWidth() == 0) return;
    double[] fit = Framing.fit(stage.getWidth(), stage.getHeight(), ratio);
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams(
            (int) Math.round(fit[0]), (int) Math.round(fit[1]), Gravity.CENTER);
    frame.setLayoutParams(lp);
    if (camera != null) frame.post(() -> camera.updateTransform(zoom));
  }

  private GrainCamera.Snapshot snapshot() {
    return new GrainCamera.Snapshot(recipe, strengths[recipe], zoom, ratio, keepFull, ev);
  }

  private void selectRecipe(int id) {
    if (busy) return;
    recipe = id;
    savePrefs();
    updateLabels();
    camera.update(snapshot());
  }

  private void updateLabels() {
    if (filmLabel == null) return;
    filmLabel.setText("•  " + Recipe.ALL[recipe].name.toUpperCase(Locale.ROOT) + " / ISP");
    ratioButton.setText(ratio);
    ratioButton.setCompoundDrawablesWithIntrinsicBounds(
        null, null, new GrainStyle.Symbol(this, "down", PAPER, 9), null);
    ratioButton.setCompoundDrawablePadding(dp(4));
    ratioButton.setPadding(dp(8), 0, dp(6), 0);
    zoomButton.setText(String.format(Locale.US, "%.1f×", zoom));
    zoomButton.setCompoundDrawablesWithIntrinsicBounds(
        null, null, new GrainStyle.Symbol(this, "down", INK, 8), null);
    zoomButton.setPadding(dp(6), 0, dp(4), 0);
    zoomButton.setCompoundDrawablePadding(dp(2));
    exposureButton.setText(String.format(Locale.US, "%+.1f", ev));
    GrainStyle.leading(exposureButton, "sun", PAPER, 12);
    exposureButton.setPadding(dp(9), 0, dp(7), 0);
    if (timerSeconds == 0) {
      GrainStyle.icon(timerButton, "timer", INK, 20);
      timerButton.setPadding(dp(9), 0, 0, 0);
    } else {
      timerButton.setCompoundDrawables(null, null, null, null);
      timerButton.setPadding(0, 0, 0, 0);
      timerButton.setText(timerSeconds + "s");
      timerButton.setTypeface(Typeface.MONOSPACE);
    }
    timerButton.setTextColor(timerSeconds == 0 ? INK : ORANGE);
    tuneButton.setText("Tune film  " + strengths[recipe] + "%");
    for (int i = 0; i < filmRow.getChildCount(); i++) {
      TextView chip = (TextView) filmRow.getChildAt(i);
      boolean active = i == recipe;
      chip.setBackground(bg(active ? INK : PAPER, 30, 0, 0));
      chip.setTextColor(active ? PAPER : MUTED);
    }
    overlay.invalidate();
  }

  private void setZoom(double value) {
    if (busy) return;
    zoom = Framing.zoom(value);
    updateLabels();
    camera.update(snapshot());
    overlay.showZoom();
    if (activeZoomValue != null) {
      activeZoomValue.setText(String.format(Locale.US, "%.1f×", zoom));
      activeZoomRange.setProgress((int) Math.round((zoom - 1) * 10));
      activeZoomNote.setText(
          zoom < 1.05
              ? "1× keeps the most source detail."
              : String.format(
                  Locale.US, "%.1f× digital crop. Closer framing, less source detail.", zoom));
    }
  }

  private void showRatio() {
    if (busy) return;
    if ("ratio".equals(cameraTool)) {
      dismissCameraTool();
      return;
    }
    LinearLayout body = openCameraTool("ratio", true);
    toolHeading(body, "Photo shape", null);
    LinearLayout options = row();
    body.addView(options, new LinearLayout.LayoutParams(-1, -2));
    String[] names = {"Classic", "Square", "Story"};
    for (int i = 0; i < Framing.RATIOS.length; i++) {
      String value = Framing.RATIOS[i];
      boolean selected = value.equals(ratio);
      int color = selected ? 0xffffddb0 : PAPER;
      LinearLayout card = column();
      card.setGravity(Gravity.CENTER);
      card.setPadding(dp(5), dp(12), dp(5), dp(10));
      card.setBackground(
          bg(selected ? 0x16f4ca90 : 0x08ffffff, 7, selected ? 0xfff4ca90 : 0x33ffffff, 1));
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(108), 1);
      cp.setMargins(i == 0 ? 0 : dp(7), 0, 0, 0);
      options.addView(card, cp);
      card.addView(new GrainStyle.RatioShape(this, value, color), size(36, 34));
      card.addView(text(value, 11, color), size(-1, 26));
      card.addView(text(names[i], 9, 0xffc3cbb9));
      card.setContentDescription(value + ", " + names[i] + (selected ? ", selected" : ""));
      card.setSelected(selected);
      card.setClickable(true);
      card.setFocusable(true);
      card.setOnClickListener(
          v -> {
            ratio = value;
            savePrefs();
            layoutFrame();
            updateLabels();
            camera.update(snapshot());
            dismissCameraTool();
          });
    }
    TextView note =
        text(
            keepFull
                ? "Full-frame recipe capture kept separately."
                : "Full-frame copy is off. Only this crop will be kept.",
            9,
            0xffb3bfa4);
    note.setGravity(Gravity.START);
    note.setPadding(0, dp(12), 0, 0);
    body.addView(note);
  }

  private void showZoom() {
    if (busy) return;
    if ("zoom".equals(cameraTool)) {
      dismissCameraTool();
      return;
    }
    LinearLayout body = openCameraTool("zoom", false);
    toolHeading(body, "Find your frame", "DIGITAL ZOOM");
    LinearLayout meter = row();
    body.addView(meter, new LinearLayout.LayoutParams(-1, dp(37)));
    activeZoomValue = text(String.format(Locale.US, "%.1f×", zoom), 24, 0xfff5d3a7);
    activeZoomValue.setTypeface(Typeface.SERIF);
    activeZoomValue.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    meter.addView(activeZoomValue, size(57, 37));
    activeZoomRange = styledSlider(40, (int) Math.round((zoom - 1) * 10));
    activeZoomRange.setContentDescription("Digital zoom, one to five times");
    meter.addView(activeZoomRange, new LinearLayout.LayoutParams(0, dp(35), 1));
    activeZoomRange.setOnSeekBarChangeListener(seek(p -> setZoom(1 + p / 10.0)));
    LinearLayout stops = row();
    stops.setPadding(dp(57), 0, 0, 0);
    body.addView(stops);
    for (int i = 1; i <= 5; i++) {
      int z = i;
      TextView stop = button(i + "×", 9, 0xffc3ceba, () -> setZoom(z));
      stop.setMinHeight(0);
      stops.addView(stop, new LinearLayout.LayoutParams(0, dp(27), 1));
    }
    View line = new View(this);
    line.setBackgroundColor(0x22ffffff);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
    lp.setMargins(0, dp(8), 0, dp(9));
    body.addView(line, lp);
    activeZoomNote =
        text(
            zoom < 1.05
                ? "1× keeps the most source detail."
                : String.format(
                    Locale.US, "%.1f× digital crop. Closer framing, less source detail.", zoom),
            9,
            0xffb3bfa4);
    activeZoomNote.setGravity(Gravity.START);
    body.addView(activeZoomNote);
  }

  private void showExposure() {
    if (busy) return;
    if ("exposure".equals(cameraTool)) {
      dismissCameraTool();
      return;
    }
    LinearLayout body = openCameraTool("exposure", false);
    toolHeading(body, "A little more light", null);
    LinearLayout labels = row();
    TextView value = text(String.format(Locale.US, "%+.1f EV", ev), 11, PAPER);
    labels.addView(value, new LinearLayout.LayoutParams(0, dp(26), 1));
    TextView reset =
        button(
            "Reset",
            10,
            0xfff4c3a3,
            () -> {
              ev = 0;
              updateLabels();
              camera.update(snapshot());
              dismissCameraTool();
            });
    labels.addView(reset, size(47, 26));
    body.addView(labels);
    SeekBar slider = styledSlider(40, Math.round((ev + 2) * 10));
    slider.setContentDescription("Exposure compensation");
    body.addView(slider, new LinearLayout.LayoutParams(-1, dp(32)));
    slider.setOnSeekBarChangeListener(
        seek(
            p -> {
              ev = (p - 20) / 10f;
              value.setText(String.format(Locale.US, "%+.1f EV", ev));
              updateLabels();
              camera.update(snapshot());
            }));
  }

  private void showFilmLab() {
    if (busy) return;
    dismissCameraTool();
    Dialog dialog = new Dialog(this);
    LinearLayout body = column();
    body.setPadding(0, dp(8), 0, dp(20));
    body.setBackground(bg(PAPER, 25, 0, 0));
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
    ArrayList<LinearLayout> tiles = new ArrayList<>();
    for (Recipe f : Recipe.ALL) {
      LinearLayout tile = column();
      tile.setPadding(dp(5), dp(5), dp(5), dp(5));
      LinearLayout.LayoutParams tp = size(97, 157);
      tp.setMargins(0, 0, dp(11), 0);
      cards.addView(tile, tp);
      tiles.add(tile);
      ImageView art = new ImageView(this);
      art.setScaleType(ImageView.ScaleType.CENTER_CROP);
      try (java.io.InputStream in = getAssets().open("film-reference.jpg")) {
        BitmapFactory.Options artworkOptions = new BitmapFactory.Options();
        artworkOptions.inSampleSize = 4;
        art.setImageBitmap(BitmapFactory.decodeStream(in, null, artworkOptions));
      } catch (Exception ignored) {
      }
      float[][] artGrades = {
        {1.03f, 1, .93f}, {.92f, 1.02f, 1.06f}, {1.1f, 1.01f, .84f}, {.74f, .8f, .88f}
      };
      float[] grade = artGrades[f.id];
      art.setColorFilter(
          new ColorMatrixColorFilter(
              new float[] {
                grade[0], 0, 0, 0, 0, 0, grade[1], 0, 0, 0, 0, 0, grade[2], 0, 0, 0, 0, 0, 1, 0
              }));
      art.setContentDescription("Film collection reference artwork, not a live recipe preview");
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
      TextView title = text(f.name, 14, INK);
      title.setTypeface(Typeface.SERIF);
      title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      title.setPadding(dp(3), 0, 0, 0);
      tile.addView(title, new LinearLayout.LayoutParams(-1, dp(29)));
      TextView iso = text("ISP RECIPE", 8, MUTED);
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
            for (int i = 0; i < tiles.size(); i++) styleFilmTile(tiles.get(i), i == recipe);
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
    TextView artNote = text("Reference artwork, not exact recipe previews", 8, MUTED);
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
    dialog.setContentView(body);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setDimAmount(.08f);
      window.setGravity(Gravity.BOTTOM);
      window.setLayout(-1, -2);
    }
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
          overlay.invalidate();
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
    sectionLabel(body, "YOUR PHOTOS. YOUR DEVICE.");
    preferenceInfo(body, "Photo storage", "No accounts. Nothing uploaded.", "Private on-device");
    preferenceInfo(
        body, "Preferences", "Film, strength, grid, ratio, timer.", "Remembered locally");
    preferenceInfo(
        body, "Photo format", "Full-resolution stills, not video frames.", "JPEG · Camera2");
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
              grid = false;
              keepFull = true;
              larger = false;
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
            "A real camera. Experimental ISP recipes. Your phone may override processing"
                + " requests—each photo keeps a receipt. No RAW, HEIF, artificial grain, or"
                + " unfiltered-original promises in this beta."));
    TextView signature = text("grain. / Made for the feeling of it.", 11, MUTED);
    signature.setTypeface(Typeface.SERIF);
    LinearLayout.LayoutParams sig = new LinearLayout.LayoutParams(-1, dp(56));
    sig.topMargin = dp(12);
    body.addView(signature, sig);
  }

  private void takePhoto() {
    dismissCameraTool();
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
    if (timerSeconds > 0) {
      counting = true;
      int token = ++countdownToken;
      countdown(timerSeconds, token);
      return;
    }
    captureNow();
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
      if (status != null) status.setText("Timer cancelled · no photo taken");
    }
  }

  private void captureNow() {
    busy = true;
    camera.neutralPreview(false);
    shutter.setText("·");
    shutter.setContentDescription("Capturing photo");
    status.setText("Capturing full-resolution JPEG…");
    shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    camera.capture(snapshot());
  }

  @Override
  public void ready(JSONObject caps, int w, int h) {
    capabilities = caps;
    sourceWidth = w;
    sourceHeight = h;
    ready = true;
    status.setText("JPEG " + caps.optString("selectedJpeg") + " · experimental ISP recipes");
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
                          : warn
                              ? "Saved. ISP limitations are recorded in this photo’s receipt."
                              : "A moment, kept.");
                });
          } catch (Exception e) {
            runOnUiThread(() -> error("Could not save photo: " + e.getMessage()));
          }
        });
  }

  private void openCamera() {
    if (!resumed || !texture.isAvailable() || camera == null) return;
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
    if (texture != null && texture.isAvailable()) openCamera();
  }

  @Override
  protected void onPause() {
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
    main.removeCallbacksAndMessages(null);
    files.shutdown();
    super.onDestroy();
  }

  @Override
  public boolean onKeyDown(int code, KeyEvent event) {
    if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
        && "camera".equals(page)) {
      if (event.getRepeatCount() == 0) takePhoto();
      return true;
    }
    return super.onKeyDown(code, event);
  }

  private void navigateBack() {
    if (cameraToolLayer != null) {
      dismissCameraTool();
      return;
    }
    if (counting) cancelTimer();
    else if ("detail".equals(page)) showGallery();
    else if (!"camera".equals(page)) closePanel();
    else finish();
  }

  private Bitmap thumbBitmap;

  private void updateThumb() {
    files.execute(
        () -> {
          java.util.List<PhotoStore.Photo> list = store.list();
          Bitmap image = list.isEmpty() ? null : store.thumbnail(list.get(0).image, 100);
          runOnUiThread(
              () -> {
                if (isDestroyed()) {
                  if (image != null) image.recycle();
                  return;
                }
                photoButton.setText("");
                photoButton.setCompoundDrawables(null, null, null, null);
                photoButton.setContentDescription("Open photos, " + list.size() + " saved");
                photoBadge.setText(String.valueOf(list.size()));
                photoBadge.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
                Bitmap old = thumbBitmap;
                thumbBitmap = image;
                if (image != null)
                  photoButton.setBackground(new GrainStyle.PhotoThumb(this, image));
                else {
                  photoButton.setBackground(bg(0xffe7ebde, 6, LINE, 1));
                  GrainStyle.icon(photoButton, "grid", MUTED, 20);
                  photoButton.setPadding(dp(11), 0, 0, 0);
                }
                if (old != null && old != image && !old.isRecycled()) old.recycle();
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
                "%.1f× DIGITAL CROP · JPEG",
                photo.metadata.optDouble("digitalZoom", 1))));
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
              "Full-frame means no framing crop or digital zoom. The camera’s recipe is already"
                  + " baked in. This is not an unfiltered original."));
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

  private void showJson(String title, JSONObject json) {
    ScrollView scroll = new ScrollView(this);
    TextView text = text(json.toString(), 10, INK);
    try {
      text.setText(json.toString(2));
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
              clip.setPrimaryClip(ClipData.newPlainText(title, json.toString()));
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
    recipe = Math.max(0, Math.min(3, preferences.getInt("recipe", 0)));
    for (int i = 0; i < 4; i++)
      strengths[i] = Math.max(0, Math.min(100, preferences.getInt("strength" + i, 80)));
    ratio = Framing.ratio(preferences.getString("ratio", "3:4"));
    grid = preferences.getBoolean("grid", false);
    keepFull = preferences.getBoolean("keepFull", true);
    larger = preferences.getBoolean("larger", false);
    timerSeconds = preferences.getInt("timer", 0);
    if (timerSeconds != 3 && timerSeconds != 5) timerSeconds = 0;
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
            .putInt("timer", timerSeconds);
    for (int i = 0; i < 4; i++) edit.putInt("strength" + i, strengths[i]);
    edit.apply();
  }

  private void message(String text) {
    if (!isDestroyed()) showSnackbar(text, null, null);
  }

  private void showSnackbar(String text, String action, Runnable onAction) {
    if (snackbar != null) root.removeView(snackbar);
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
                root.removeView(bar);
                onAction.run();
              });
      bar.addView(button, size(58, 40));
    }
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    lp.setMargins(dp(15), 0, dp(15), dp(18));
    root.addView(bar, lp);
    snackbar = bar;
    main.postDelayed(
        () -> {
          root.removeView(bar);
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
    if (cameraToolLayer != null) {
      stage.removeView(cameraToolLayer);
      cameraToolLayer = null;
    }
    cameraTool = null;
    activeZoomValue = null;
    activeZoomRange = null;
    activeZoomNote = null;
  }

  private LinearLayout openCameraTool(String name, boolean top) {
    dismissCameraTool();
    cameraTool = name;
    cameraToolLayer = new FrameLayout(this);
    cameraToolLayer.setOnClickListener(v -> dismissCameraTool());
    stage.addView(cameraToolLayer, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout body = column();
    body.setClickable(true);
    body.setPadding(dp(14), dp(13), dp(14), dp(13));
    body.setElevation(dp(10));
    body.setBackground(bg(0xf5263020, 12, 0x44ffffff, 1));
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-1, -2, top ? Gravity.TOP : Gravity.BOTTOM);
    params.setMargins(dp(14), dp(54), dp(14), top ? 0 : dp(58));
    cameraToolLayer.addView(body, params);
    body.setAlpha(0);
    body.animate().alpha(1).setDuration(130).start();
    return body;
  }

  private void toolHeading(LinearLayout body, String title, String tag) {
    LinearLayout head = row();
    TextView label = text(title, 11, PAPER);
    label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    head.addView(label, new LinearLayout.LayoutParams(0, dp(28), 1));
    if (tag != null) {
      TextView badge = text(tag, 7, 0xfff4ca90);
      badge.setTypeface(Typeface.MONOSPACE);
      badge.setPadding(dp(5), dp(3), dp(5), dp(3));
      badge.setBackground(bg(0x11f4ca90, 4, 0x55f4ca90, 1));
      head.addView(badge);
      View gap = new View(this);
      head.addView(gap, size(10, 1));
    }
    TextView close = iconButton("close", PAPER, 11, this::dismissCameraTool);
    close.setMinHeight(0);
    close.setPadding(dp(6), 0, 0, 0);
    close.setBackground(bg(0, 30, 0x44ffffff, 1));
    close.setContentDescription("Close " + title);
    head.addView(close, size(24, 24));
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
    hp.bottomMargin = dp(11);
    body.addView(head, hp);
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

  private TextView tool(String value, Runnable click) {
    TextView t = button(value, 10, PAPER, click);
    t.setMinHeight(0);
    t.setBackground(bg(0x88303c29, 18, 0x44ffffff, 1));
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

  private final class GridOverlay extends View {
    private final Paint paint = new Paint(3);
    private float fx, fy;
    private long focusAt, zoomAt;

    GridOverlay() {
      super(MainActivity.this);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void focus(float x, float y) {
      fx = x;
      fy = y;
      focusAt = SystemClock.uptimeMillis();
      invalidate();
      postDelayed(this::invalidate, 1200);
    }

    void showZoom() {
      zoomAt = SystemClock.uptimeMillis();
      invalidate();
      postDelayed(this::invalidate, 900);
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      paint.setStrokeWidth(dp(1));
      paint.setStyle(Paint.Style.STROKE);
      paint.setColor(0x88ffffff);
      float pad = dp(9), edge = dp(15), right = getWidth() - pad, bottom = getHeight() - pad;
      c.drawLine(pad, pad, pad + edge, pad, paint);
      c.drawLine(pad, pad, pad, pad + edge, paint);
      c.drawLine(right, pad, right - edge, pad, paint);
      c.drawLine(right, pad, right, pad + edge, paint);
      c.drawLine(pad, bottom, pad + edge, bottom, paint);
      c.drawLine(pad, bottom, pad, bottom - edge, paint);
      c.drawLine(right, bottom, right - edge, bottom, paint);
      c.drawLine(right, bottom, right, bottom - edge, paint);
      if (grid) {
        paint.setColor(0x77ffffff);
        for (int n = 1; n < 3; n++) {
          c.drawLine(getWidth() * n / 3f, 0, getWidth() * n / 3f, getHeight(), paint);
          c.drawLine(0, getHeight() * n / 3f, getWidth(), getHeight() * n / 3f, paint);
        }
      }
      if (SystemClock.uptimeMillis() - focusAt < 1100) {
        paint.setColor(0xffffdb97);
        float r = dp(25);
        c.drawRoundRect(fx - r, fy - r, fx + r, fy + r, dp(3), dp(3), paint);
      }
      if (SystemClock.uptimeMillis() - zoomAt < 850) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xbb263020);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        c.drawRoundRect(cx - dp(46), cy - dp(27), cx + dp(46), cy + dp(27), dp(10), dp(10), paint);
        paint.setColor(PAPER);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(23));
        c.drawText(String.format(Locale.US, "%.1f×", zoom), cx, cy + dp(7), paint);
      }
    }
  }
}
