package dev.grain.photo;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Grain's main screen drawn as the back of a Fujifilm camera: a top plate with the film, shutter
 * and exposure dials, the screen, the memo holder and the buttons. The screen is a window: the
 * camera preview lives underneath (in screen coordinates, never rotated) and this layer draws the
 * camera info over it. Portrait and landscape are two layouts of the same parts; the activity
 * turns this whole layer with the phone, so the capture pipeline never sees a rotation. Pure UI:
 * every control is reported to the Host, and it draws whatever state the activity gives it.
 */
final class CameraBack extends ViewGroup {
  interface Host {
    /** A control was used: one of the K_ constants and its value. */
    void key(int key, int value);

    /** Tap on the picture: raw screen position and the position within the picture (0..1). */
    void lcdTap(float rawX, float rawY, float x, float y);

    /** Press-and-hold on the picture to open/close the Focus Check magnifier around the focus point. */
    void lcdHold(float x, float y, boolean held);

    void pinch(float factor);

    /** Where the camera preview must be, in the parent's (screen) coordinates. */
    void previewRect(RectF rect);

    /** True when the focus lever drives continuous 360-degree analog AF gliding. */
    default boolean leverAnalogMode() {
      return false;
    }

    /** Continuous analog focus-lever velocity (vx, vy in [-1..1]) and frame delta (seconds). */
    default void leverGlide(float vx, float vy, float dtSec, boolean finished) {}
  }

  static final int K_Q = 1, K_OK = 2, K_DISP = 3, K_PLAY = 4, K_DRIVE = 5, K_AEL = 6, K_LEVER = 7,
      K_COMMAND = 8, K_EYE = 9, K_VIEW_MODE = 10, K_POWER = 11, K_MEMO = 12, K_FILM = 13, K_EV = 14,
      K_SS = 15, K_ISO_TOGGLE = 16, K_SHUTTER = 17, K_EVF_BACK = 18, K_SWIPE = 19, K_FLASH = 20;
  static final int LEVER_CENTER = 0, LEVER_UP = 1, LEVER_RIGHT = 2, LEVER_DOWN = 3, LEVER_LEFT = 4;
  static final int ORANGE = 0xffe2622f, GREEN = 0xff63d36e;
  static final String[] FINISHES = {"SILVER", "BLACK", "GRAPHITE"};

  /** Everything the screen shows; the activity fills it and calls {@link #refresh}. */
  static final class OsdState {
    String code = "", film = "", format = "", ratio = "", frames = "", mode = "P", shutter = "",
        aperture = "", iso = "", drive = "", zoom = "", status = "", countdown = "", toastTitle,
        toastSub, magLabel = "";
    final List<String> icons = new ArrayList<>();
    boolean isoAuto = true, aeLock, grid, showHistogram = true, showLevel = true, busy, infoOff,
        manual, exposureWarn, faceActive, updateAvailable;
    int battery = -1, afState, afArea;
    /** The peaking edge map, or null to draw nothing. */
    Bitmap peak;
    /** Held-to-magnify Focus Check patch around the focus point, or null when inactive. */
    Bitmap mag;
    double exposure;
    float level = Float.NaN, afX = .5f, afY = .5f, faceL, faceT, faceR, faceB;
    int[] histogram;
    long toastAt;
  }

  private final Host host;
  private final float density;
  final OsdState state = new OsdState();
  final Body body;
  final Drum film, shutterDial, exposure;
  final TextView isoChip, viewMode, flashBtn, ael, q, drive, play, ok, disp, evfBack;
  final Eyepiece eyepiece;
  final Lamp lamp;
  final Power power;
  final Osd osd;
  final FrameLayout overlay;
  final Memo memo;
  final Command command;
  final Lever lever;
  final Shutter shutter;
  private final Map<View, RectF> places = new HashMap<>();
  private final List<Engraving> engravings = new ArrayList<>();
  private Engraving shutterEngraving;
  private final RectF bezel = new RectF(), screen = new RectF(), image = new RectF(), slot = new RectF(),
      thumb = new RectF(), grille = new RectF(), overlayRect = new RectF(), isoHitRect = new RectF();
  private float plateHeight, ox, oy;
  boolean landscape, evf, on = true, isoMode;
  int finish, flashMode;
  /** Pixels per design unit (the layouts are drawn on a 400 × 860 / 860 × 400 grid). */
  float u = 1;
  private float styledU = -1;
  private double portraitRatio = .75;
  private String lens = "";
  private View mappingRoot;

  CameraBack(Context c, Host host) {
    super(c);
    this.host = host;
    density = c.getResources().getDisplayMetrics().density;
    setClipChildren(true);
    body = new Body(c);
    addView(body);
    osd = new Osd(c);
    addView(osd);
    overlay = new FrameLayout(c);
    overlay.setVisibility(GONE);
    overlay.setClickable(true);
    addView(overlay);
    eyepiece = new Eyepiece(c);
    addView(eyepiece);
    lamp = new Lamp(c);
    addView(lamp);
    viewMode = key(c, "", false, K_VIEW_MODE);
    viewMode.setContentDescription("View mode: eye sensor, viewfinder only or screen only");
    addView(viewMode);
    flashBtn = key(c, "⚡ OFF", false, K_FLASH);
    flashBtn.setContentDescription("Flash mode: off, auto, fill on or continuous torch");
    addView(flashBtn);
    power = new Power(c);
    addView(power);
    film = new Drum(c, K_FILM, 34, "Film simulation dial");
    addView(film);
    shutterDial = new Drum(c, K_SS, 30, "Shutter speed dial");
    addView(shutterDial);
    exposure = new Drum(c, K_EV, 17, "Exposure compensation dial");
    exposure.small = true;
    addView(exposure);
    isoChip = new TextView(c);
    isoChip.setText("ISO");
    isoChip.setGravity(Gravity.CENTER);
    isoChip.setIncludeFontPadding(false);
    isoChip.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    isoChip.setClickable(true);
    isoChip.setFocusable(true);
    isoChip.setContentDescription("Switch the top dial between shutter speed and ISO");
    isoChip.setOnClickListener(
        v -> {
          v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
          host.key(K_ISO_TOGGLE, 0);
        });
    addView(isoChip);
    memo = new Memo(c);
    addView(memo);
    ael = key(c, "AEL/AFL", false, K_AEL);
    ael.setContentDescription("AE lock");
    addView(ael);
    command = new Command(c);
    addView(command);
    q = key(c, "Q", true, K_Q);
    q.setContentDescription("Quick menu");
    addView(q);
    drive = key(c, "DRIVE", false, K_DRIVE);
    drive.setContentDescription(
        "Drive: single, self-timer, film simulation bracketing, focus bracketing; delete in playback");
    addView(drive);
    play = key(c, "▶", false, K_PLAY);
    play.setContentDescription("Playback");
    addView(play);
    lever = new Lever(c);
    addView(lever);
    ok = key(c, "MENU\nOK", true, K_OK);
    ok.setContentDescription("Menu, OK");
    addView(ok);
    disp = key(c, "DISP/BACK", false, K_DISP);
    disp.setContentDescription("Display, back");
    addView(disp);
    shutter = new Shutter(c);
    addView(shutter);
    evfBack = new TextView(c);
    evfBack.setText("◀  SCREEN");
    evfBack.setGravity(Gravity.CENTER);
    evfBack.setTextColor(0xffe9e5da);
    evfBack.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    evfBack.setLetterSpacing(.1f);
    evfBack.setClickable(true);
    evfBack.setFocusable(true);
    evfBack.setContentDescription("Back to the screen view");
    evfBack.setOnClickListener(v -> host.key(K_EVF_BACK, 0));
    addView(evfBack);
  }

  // ───────────────────────────── state from the activity ─────────────────────────────

  void setLandscape(boolean value) {
    if (landscape == value) return;
    landscape = value;
    requestLayout();
  }

  void setEvf(boolean value) {
    if (evf == value) return;
    evf = value;
    requestLayout();
    body.invalidate();
    osd.invalidate();
  }

  void setFinish(int value) {
    finish = Math.max(0, Math.min(2, value));
    body.prepare();
    for (View v : new View[] {film, shutterDial, exposure, power, isoChip, flashBtn}) v.invalidate();
    styleIsoChip();
    styleFlashBtn();
  }

  void setFlashMode(int value) {
    flashMode = Math.max(ExposureMath.FLASH_OFF, Math.min(ExposureMath.FLASH_TORCH, value));
    flashBtn.setText(ExposureMath.FLASH_SHORT[flashMode]);
    styleFlashBtn();
  }

  void setOn(boolean value) {
    on = value;
    power.invalidate();
    osd.invalidate();
  }

  void setIsoMode(boolean value) {
    isoMode = value;
    if (shutterEngraving != null) shutterEngraving.text = value ? "ISO" : "SHUTTER";
    shutterDial.description = value ? "ISO dial" : "Shutter speed dial";
    styleIsoChip();
    body.invalidate();
  }

  /** Width ÷ height of the photo for a phone held upright (3:4 → 0.75). */
  void setPhotoRatio(double ratio) {
    if (Math.abs(ratio - portraitRatio) < 1e-6) return;
    portraitRatio = ratio;
    requestLayout();
  }

  void setLens(String text) {
    lens = text;
    requestLayout();
  }

  void refresh() {
    osd.invalidate();
  }

  void toast(String title, String sub) {
    state.toastTitle = title;
    state.toastSub = sub;
    state.toastAt = SystemClock.uptimeMillis();
    osd.invalidate();
  }

  /** Q menu, MENU and playback sit on the screen (the picture in the viewfinder view). */
  void showOverlay(View content) {
    overlay.removeAllViews();
    overlay.addView(content, new FrameLayout.LayoutParams(-1, -1));
    overlay.setVisibility(VISIBLE);
  }

  void hideOverlay() {
    overlay.removeAllViews();
    overlay.setVisibility(GONE);
  }

  boolean overlayShown() {
    return overlay.getVisibility() == VISIBLE;
  }

  /** The view whose coordinates the preview is placed in (the activity's root). */
  void setMappingRoot(View root) {
    mappingRoot = root;
  }

  /** The screen (or, in the viewfinder view, the picture) in this layer's coordinates. */
  RectF screenRect() {
    return new RectF(evf ? image : screen);
  }

  /** Maps points of this layer to the mapping root, through every turned or moved ancestor. */
  private void mapToRoot(float[] points) {
    View v = this;
    while (v != null && v != mappingRoot) {
      Matrix m = new Matrix(v.getMatrix());
      m.postTranslate(v.getLeft(), v.getTop());
      m.mapPoints(points);
      ViewParent parent = v.getParent();
      v = parent instanceof View ? (View) parent : null;
    }
  }

  /** A point of the picture (0..1 each way) in the mapping root's coordinates. */
  float[] imagePointToRoot(float x, float y) {
    float[] point = {image.left + x * image.width(), image.top + y * image.height()};
    mapToRoot(point);
    return point;
  }

  /** Tells the host where the camera preview must go: under the picture, in root coordinates. */
  void reportPreview() {
    if (image.isEmpty() || getWidth() == 0) return;
    float[] corners = {image.left, image.top, image.right, image.top, image.right, image.bottom, image.left, image.bottom};
    mapToRoot(corners);
    float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
    for (int i = 0; i < 8; i += 2) {
      l = Math.min(l, corners[i]);
      r = Math.max(r, corners[i]);
      t = Math.min(t, corners[i + 1]);
      b = Math.max(b, corners[i + 1]);
    }
    host.previewRect(new RectF(l, t, r, b));
  }

  // ───────────────────────────── layout ─────────────────────────────

  @Override
  public void addView(View child) {
    super.addView(child, new LayoutParams(0, 0));
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
    setMeasuredDimension(w, h);
    computeLayout(w, h);
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      RectF r = places.get(child);
      if (r == null) {
        child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
        continue;
      }
      int cw = Math.round(r.width()), ch = Math.round(r.height());
      LayoutParams lp = child.getLayoutParams();
      if (lp != null && (lp.width != cw || lp.height != ch)) {
        lp.width = cw;
        lp.height = ch;
      }
      child.measure(
          MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
    }
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      RectF p = places.get(child);
      if (p == null) {
        child.layout(0, 0, 0, 0);
        continue;
      }
      int left = Math.round(p.left), top = Math.round(p.top);
      child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
    }
    installTouchDelegates();
    post(this::reportPreview);
  }

  private void installTouchDelegates() {
    if (evf) {
      setTouchDelegate(null);
      return;
    }
    MultiTouchDelegate group = new MultiTouchDelegate(this);
    if (!isoHitRect.isEmpty() && isoChip.getVisibility() == VISIBLE) {
      group.add(roundRect(isoHitRect), isoChip);
    }
    RectF vm = places.get(viewMode);
    if (vm != null && viewMode.getVisibility() == VISIBLE) {
      Rect pad = roundRect(vm);
      pad.inset(Math.round(-3 * u), Math.round(-6 * u));
      group.add(pad, viewMode);
    }
    RectF fl = places.get(flashBtn);
    if (fl != null && flashBtn.getVisibility() == VISIBLE) {
      Rect pad = roundRect(fl);
      pad.inset(Math.round(-3 * u), Math.round(-6 * u));
      group.add(pad, flashBtn);
    }
    setTouchDelegate(group);
  }

  private static Rect roundRect(RectF f) {
    return new Rect(Math.round(f.left), Math.round(f.top), Math.round(f.right), Math.round(f.bottom));
  }

  private static final class MultiTouchDelegate extends TouchDelegate {
    private final List<TouchDelegate> delegates = new ArrayList<>();
    private TouchDelegate active;

    MultiTouchDelegate(View parent) {
      super(new Rect(), parent);
    }

    void add(Rect bounds, View child) {
      delegates.add(new TouchDelegate(bounds, child));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      int action = event.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) {
        active = null;
        for (TouchDelegate d : delegates) {
          MotionEvent copy = MotionEvent.obtain(event);
          boolean handled = d.onTouchEvent(copy);
          copy.recycle();
          if (handled) {
            active = d;
            return true;
          }
        }
        return false;
      }
      TouchDelegate current = active;
      if (current == null) return false;
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        active = null;
      }
      MotionEvent copy = MotionEvent.obtain(event);
      boolean handled = current.onTouchEvent(copy);
      copy.recycle();
      return handled;
    }
  }

  private RectF d(float x, float y, float w, float h) {
    return new RectF(ox + x * u, oy + y * u, ox + (x + w) * u, oy + (y + h) * u);
  }

  private void put(View v, RectF r) {
    places.put(v, r);
  }

  private Engraving engrave(String text, float x, float y, float size, Paint.Align align, boolean plate) {
    Engraving e = new Engraving(text, ox + x * u, oy + y * u, size * u, align, plate);
    engravings.add(e);
    return e;
  }

  private void fit(RectF area, double widthOverHeight, RectF out) {
    float w = area.width(), h = area.height();
    if (w <= 0 || h <= 0) {
      out.setEmpty();
      return;
    }
    float fw = (float) Math.min(w, h * widthOverHeight), fh = (float) (fw / widthOverHeight);
    out.set(area.centerX() - fw / 2, area.centerY() - fh / 2, area.centerX() + fw / 2, area.centerY() + fh / 2);
  }

  private void computeLayout(int w, int h) {
    places.clear();
    engravings.clear();
    shutterEngraving = null;
    bezel.setEmpty();
    screen.setEmpty();
    slot.setEmpty();
    thumb.setEmpty();
    grille.setEmpty();
    isoHitRect.setEmpty();
    if (w == 0 || h == 0) return;
    double ratio = landscape ? 1 / portraitRatio : portraitRatio;
    if (evf) layoutEvf(w, h, ratio);
    else if (landscape) layoutLandscape(w, h, ratio);
    else layoutPortrait(w, h, ratio);
    boolean screenParts = !evf;
    for (View v : new View[] {eyepiece, lamp, viewMode, flashBtn, power, film, shutterDial, exposure, isoChip, memo, ael, command, q, drive, play, lever, ok, disp, shutter})
      v.setVisibility(screenParts ? VISIBLE : GONE);
    evfBack.setVisibility(GONE);
    put(body, new RectF(0, 0, w, h));
    if (u != styledU) {
      // Only when the scale changes: restyling inside measure must not request another layout.
      styledU = u;
      for (TextView k : new TextView[] {ael, drive, play, disp}) styleKey(k, 6.6f);
      styleKey(q, 15);
      styleKey(ok, 6.8f);
      styleKey(viewMode, 6);
      evfBack.setTextSize(TypedValue.COMPLEX_UNIT_PX, 7.4f * u);
      evfBack.setBackground(pill(0x99000000, 0x55ffffff));
      styleIsoChip();
      styleFlashBtn();
    }
    body.prepare();
  }

  private void layoutPortrait(int w, int h, double ratio) {
    float extra;
    if (w / 400f <= h / 860f) {
      u = w / 400f;
      ox = 0;
      extra = h / u - 860;
    } else {
      u = h / 860f;
      ox = (w - 400 * u) / 2;
      extra = 0;
    }
    oy = 0;
    plateHeight = 158 * u;
    put(eyepiece, d(16, 12, 112, 46));
    put(lamp, d(137, 27, 6, 6));
    put(viewMode, d(136, 40, 40, 15));
    engrave("FLASH", 206, 35, 5.2f, Paint.Align.CENTER, true);
    put(flashBtn, d(183, 40, 46, 15));
    put(power, d(316, 10, 66, 24));
    engrave("OFF", 318, 45, 6.2f, Paint.Align.LEFT, true);
    engrave("ON", 382, 45, 6.2f, Paint.Align.RIGHT, true);
    engrave(lens, 274, 30, 6.2f, Paint.Align.CENTER, true);
    put(shutterDial, d(16, 68, 176, 32));
    shutterEngraving = engrave(isoMode ? "ISO" : "SHUTTER", 96, 112, 6.6f, Paint.Align.CENTER, true);
    put(isoChip, d(158, 103, 34, 13));
    isoHitRect.set(d(58, 101, 136, 18));
    put(exposure, d(208, 68, 176, 32));
    engrave("EXPOSURE  ±", 296, 112, 6.6f, Paint.Align.CENTER, true);
    put(film, d(16, 120, 368, 30));
    bezel.set(d(21, 166, 358, 472 + extra));
    screen.set(bezel.left + 7 * u, bezel.top + 7 * u, bezel.right - 7 * u, bezel.bottom - 7 * u);
    float by = 638 + extra;
    put(ael, d(18, by + 16, 54, 22));
    put(command, d(84, by + 12, 222, 30));
    slot.set(d(81, by + 9, 228, 36));
    engrave("COMMAND", 195, by + 55, 6.2f, Paint.Align.CENTER, false);
    put(q, d(332, by + 7, 46, 46));
    put(memo, d(16, by + 62, 54, 134));
    engrave("DRIVE / ◷", 86, by + 64, 5.8f, Paint.Align.LEFT, false);
    put(drive, d(84, by + 68, 54, 24));
    engrave("PLAY", 86, by + 103, 5.8f, Paint.Align.LEFT, false);
    put(play, d(84, by + 107, 54, 24));
    put(disp, d(78, by + 146, 66, 24));
    put(shutter, d(160, by + 60, 116, 116));
    put(lever, d(296, by + 58, 74, 74));
    put(ok, d(307, by + 144, 52, 52));
    fit(screen, ratio, image);
    placeScreen(screen);
  }

  private void layoutLandscape(int w, int h, double ratio) {
    float extra;
    if (w / 860f <= h / 400f) {
      u = w / 860f;
      oy = (h - 400 * u) / 2;
      extra = 0;
    } else {
      u = h / 400f;
      oy = 0;
      extra = w / u - 860;
    }
    ox = 0;
    plateHeight = oy + 66 * u;
    put(eyepiece, d(32, 8, 126, 50));
    put(flashBtn, d(164, 11, 48, 15));
    put(lamp, d(216, 28, 5, 5));
    put(viewMode, d(164, 36, 48, 15));
    engrave("VIEW MODE", 188, 60, 5.2f, Paint.Align.CENTER, true);
    put(film, d(226, 10, 250 + extra, 32));
    engrave("FILM SIMULATION", 351 + extra / 2, 54, 6.4f, Paint.Align.CENTER, true);
    put(shutterDial, d(494 + extra, 10, 124, 32));
    shutterEngraving = engrave(isoMode ? "ISO" : "SHUTTER", 541 + extra, 54, 6.4f, Paint.Align.CENTER, true);
    put(isoChip, d(586 + extra, 45, 32, 13));
    isoHitRect.set(d(504 + extra, 43, 116, 20));
    put(exposure, d(636 + extra, 10, 116, 32));
    engrave("EXPOSURE  ±", 694 + extra, 54, 6.4f, Paint.Align.CENTER, true);
    put(power, d(772 + extra, 10, 66, 24));
    engrave("OFF", 774 + extra, 44, 5.8f, Paint.Align.LEFT, true);
    engrave("ON", 838 + extra, 44, 5.8f, Paint.Align.RIGHT, true);
    engrave(lens, 805 + extra, 59, 5.4f, Paint.Align.CENTER, true);
    bezel.set(d(30, 80, 402 + extra, 304));
    screen.set(bezel.left + 7 * u, bezel.top + 7 * u, bezel.right - 7 * u, bezel.bottom - 7 * u);
    float rx = extra;
    put(memo, d(448 + rx, 96, 64, 222));
    engrave("MEMO", 452 + rx, 333, 5.8f, Paint.Align.LEFT, false);
    put(ael, d(532 + rx, 86, 54, 22));
    put(command, d(600 + rx, 80, 164, 30));
    slot.set(d(597 + rx, 77, 170, 36));
    engrave("COMMAND", 682 + rx, 124, 6.2f, Paint.Align.CENTER, false);
    put(q, d(788 + rx, 76, 46, 46));
    engrave("DRIVE / ◷", 534 + rx, 131, 5.8f, Paint.Align.LEFT, false);
    put(drive, d(532 + rx, 135, 54, 24));
    engrave("PLAY", 534 + rx, 171, 5.8f, Paint.Align.LEFT, false);
    put(play, d(532 + rx, 175, 54, 24));
    put(lever, d(601 + rx, 134, 72, 72));
    put(ok, d(611 + rx, 218, 52, 52));
    put(disp, d(604 + rx, 282, 66, 24));
    put(shutter, d(700 + rx, 138, 118, 118));
    thumb.set(d(694 + rx, 296, 140, 86));
    grille.set(d(534 + rx, 334, 54, 22));
    fit(screen, ratio, image);
    placeScreen(screen);
  }

  private void layoutEvf(int w, int h, double ratio) {
    plateHeight = 0;
    if (landscape) {
      if (w / 860f <= h / 400f) {
        u = w / 860f;
        oy = (h - 400 * u) / 2;
      } else {
        u = h / 400f;
        oy = 0;
      }
      ox = 0;
      image.set(d(32, 8, 152, 102));
    } else {
      if (w / 400f <= h / 860f) {
        u = w / 400f;
        ox = 0;
      } else {
        u = h / 860f;
        ox = (w - 400 * u) / 2;
      }
      oy = 0;
      RectF vf = d(16, 12, 136, 92);
      WindowInsets rootInsets = getRootWindowInsets();
      DisplayCutout cutout = rootInsets != null ? rootInsets.getDisplayCutout() : null;
      if (cutout != null) {
        Rect vfRound = roundRect(vf);
        for (Rect cr : cutout.getBoundingRects()) {
          if (Rect.intersects(cr, vfRound) && cr.bottom < h / 4) {
            float hVf = vf.height();
            vf.top = cr.bottom + 4 * u;
            vf.bottom = vf.top + hVf;
          }
        }
      }
      image.set(vf);
    }
    put(osd, new RectF(0, 0, w, h));
    overlayRect.set(image);
    put(overlay, new RectF(overlayRect));
  }

  private void placeScreen(RectF area) {
    put(osd, new RectF(area));
    overlayRect.set(area);
    put(overlay, new RectF(area));
  }

  // ───────────────────────────── finishes ─────────────────────────────

  private static final int[][] PLATE = {
    {0xfff4f3ee, 0xffdfded7, 0xffc4c3bc, 0xffe3e2dc},
    {0xff3c3d3a, 0xff282926, 0xff1b1c1a, 0xff2f302d},
    {0xff939490, 0xff747571, 0xff5b5c58, 0xff7e7f7b}
  };
  private static final float[] PLATE_STOPS = {0, .45f, .8f, 1};
  private static final int[][] DRUM = {
    {0xff8c8b85, 0xfff4f3ee, 0xff97968f},
    {0xff0d0e0c, 0xff4f504c, 0xff121311},
    {0xff292a27, 0xff8e8f8a, 0xff2f302c}
  };

  int plateInk() {
    return finish == 0 ? 0xff3a3c37 : finish == 1 ? 0xffd6d1c6 : 0xfff1f0eb;
  }

  int drumInk() {
    return finish == 0 ? 0xff2a2c27 : 0xfff2eee5;
  }

  int drumAccent() {
    return finish == 0 ? 0xffd4502a : 0xffff8d5f;
  }

  private void styleIsoChip() {
    GradientDrawable g = new GradientDrawable();
    g.setCornerRadius(20 * u);
    g.setStroke(Math.max(1, Math.round(.9f * u)), isoMode ? ORANGE : plateInk());
    g.setColor(isoMode ? ORANGE : 0x18000000);
    isoChip.setBackground(g);
    isoChip.setTextColor(isoMode ? 0xffffffff : plateInk());
    isoChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, 6.6f * u);
  }

  private void styleFlashBtn() {
    boolean active = flashMode != ExposureMath.FLASH_OFF;
    if (active) {
      GradientDrawable g = new GradientDrawable();
      g.setCornerRadius(4.2f * u);
      g.setColor(ORANGE);
      g.setStroke(Math.max(1, Math.round(.8f * u)), 0x66ffffff);
      flashBtn.setBackground(g);
      flashBtn.setTextColor(0xffffffff);
      flashBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 5.8f * u);
    } else {
      styleKey(flashBtn, 5.8f);
      flashBtn.setTextColor(0xffe9e5da);
    }
  }

  private TextView key(Context c, String label, boolean round, int id) {
    TextView v = new TextView(c);
    v.setText(label);
    v.setGravity(Gravity.CENTER);
    v.setTextColor(0xffe9e5da);
    v.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    v.setLetterSpacing(.06f);
    v.setIncludeFontPadding(false);
    v.setLineSpacing(0, .9f);
    v.setClickable(true);
    v.setFocusable(true);
    v.setTag(round ? "round" : "pill");
    v.setOnClickListener(
        x -> {
          x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
          host.key(id, 0);
        });
    return v;
  }

  private void styleKey(TextView v, float size) {
    boolean round = "round".equals(v.getTag());
    StateListDrawable s = new StateListDrawable();
    s.addState(new int[] {android.R.attr.state_pressed}, keyShape(round, true));
    s.addState(new int[] {}, keyShape(round, false));
    v.setBackground(s);
    v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
  }

  private GradientDrawable keyShape(boolean round, boolean pressed) {
    GradientDrawable g =
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            pressed ? new int[] {0xff151614, 0xff262724} : new int[] {0xff4a4b47, 0xff1e1f1c});
    if (round) g.setShape(GradientDrawable.OVAL);
    else g.setCornerRadius(40 * u);
    g.setStroke(Math.max(1, Math.round(u)), 0xff050505);
    return g;
  }

  private GradientDrawable pill(int fill, int stroke) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(fill);
    g.setCornerRadius(40 * u);
    g.setStroke(Math.max(1, Math.round(u)), stroke);
    return g;
  }

  private static Bitmap speckle(int size, float light, float dark, long seed) {
    Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    int[] px = new int[size * size];
    Random r = new Random(seed);
    for (int i = 0; i < px.length; i++) {
      float v = r.nextFloat();
      px[i] =
          v < light
              ? Color.argb(12 + r.nextInt(16), 255, 255, 255)
              : v < light + dark ? Color.argb(40 + r.nextInt(55), 0, 0, 0) : 0;
    }
    b.setPixels(px, 0, size, 0, 0, size, size);
    return b;
  }

  private static Bitmap brushedTile() {
    int w = 256, h = 64;
    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    int[] px = new int[w * h];
    Random r = new Random(3);
    for (int y = 0; y < h; y++) {
      int a = r.nextInt(20);
      int c = r.nextBoolean() ? 255 : 0;
      for (int x = 0; x < w; x++) px[y * w + x] = Color.argb(Math.max(0, a - r.nextInt(7)), c, c, c);
    }
    b.setPixels(px, 0, w, 0, 0, w, h);
    return b;
  }

  private static final class Engraving {
    String text;
    final float x, y, size;
    final Paint.Align align;
    final boolean plate;

    Engraving(String text, float x, float y, float size, Paint.Align align, boolean plate) {
      this.text = text;
      this.x = x;
      this.y = y;
      this.size = size;
      this.align = align;
      this.plate = plate;
    }
  }

  // ───────────────────────────── the body ─────────────────────────────

  final class Body extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BitmapShader leather, brushed;
    private Shader plateShader, plateShadow, light;
    private final Matrix leatherScale = new Matrix();

    Body(Context c) {
      super(c);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      leather = new BitmapShader(speckle(160, .12f, .12f, 7), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
      brushed = new BitmapShader(brushedTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
      t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
      t.setLetterSpacing(.14f);
    }

    void prepare() {
      float s = Math.max(1, density * .8f);
      leatherScale.setScale(s, s);
      leather.setLocalMatrix(leatherScale);
      float ph = Math.max(1, plateHeight);
      plateShader = new LinearGradient(0, 0, 0, ph, PLATE[finish], PLATE_STOPS, Shader.TileMode.CLAMP);
      plateShadow = new LinearGradient(0, ph, 0, ph + 12 * u, 0x90000000, 0x00000000, Shader.TileMode.CLAMP);
      float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
      light =
          new RadialGradient(
              w * .3f, h * .25f, Math.max(w, h), new int[] {0x14ffffff, 0x00000000, 0x55000000},
              new float[] {0, .55f, 1}, Shader.TileMode.CLAMP);
      invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
      prepare();
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight();
      if (evf) {
        c.save();
        if (!image.isEmpty()) {
          Path hole = new Path();
          hole.addRoundRect(image, 7 * u, 7 * u, Path.Direction.CW);
          c.clipOutPath(hole);
        }
        c.drawColor(0xff000000);
        c.restore();
        if (!image.isEmpty()) {
          p.setShader(null);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(Math.max(1, .8f * u));
          p.setColor(0x1affffff);
          c.drawRoundRect(
              image.left + .4f * u,
              image.top + .4f * u,
              image.right - .4f * u,
              image.bottom - .4f * u,
              7 * u,
              7 * u,
              p);
          p.setStyle(Paint.Style.FILL);
        }
        return;
      }
      c.save();
      if (!image.isEmpty()) c.clipOutRect(image);
      p.setShader(null);
      p.setColor(0xff1c1d1a);
      c.drawRect(0, 0, w, h, p);
      p.setShader(leather);
      c.drawRect(0, 0, w, h, p);
      if (light != null) {
        p.setShader(light);
        c.drawRect(0, 0, w, h, p);
      }
      if (plateShader != null) {
        p.setShader(plateShader);
        c.drawRect(0, 0, w, plateHeight, p);
        p.setShader(brushed);
        c.drawRect(0, 0, w, plateHeight, p);
        p.setShader(plateShadow);
        c.drawRect(0, plateHeight, w, plateHeight + 12 * u, p);
      }
      p.setShader(null);
      p.setColor(0x60000000);
      c.drawRect(0, plateHeight - u, w, plateHeight, p);
      p.setColor(0x40ffffff);
      c.drawRect(0, 0, w, Math.max(1, u * .8f), p);
      for (Drum dial : new Drum[] {film, shutterDial, exposure}) {
        RectF r = places.get(dial);
        if (r == null) continue;
        p.setColor(finish == 0 ? 0xff6f6e69 : 0xff090908);
        c.drawRoundRect(r.left - 2 * u, r.top - 2 * u, r.right + 2 * u, r.bottom + 2 * u, 7 * u, 7 * u, p);
        p.setColor(ORANGE);
        Path mark = new Path();
        float cx = r.centerX(), top = r.top - 3.4f * u;
        mark.moveTo(cx - 3.6f * u, top - 3.6f * u);
        mark.lineTo(cx + 3.6f * u, top - 3.6f * u);
        mark.lineTo(cx, top + .6f * u);
        mark.close();
        c.drawPath(mark, p);
      }
      for (Engraving e : engravings) {
        if (e.text == null || e.text.isEmpty()) continue;
        t.setTextSize(e.size);
        t.setTextAlign(e.align);
        t.setColor(e.plate ? plateInk() : 0xffa8a59b);
        c.drawText(e.text, e.x, e.y, t);
      }
      if (!bezel.isEmpty()) {
        p.setColor(0xff0a0a09);
        c.drawRoundRect(bezel, 13 * u, 13 * u, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, u));
        p.setColor(0x24ffffff);
        c.drawRoundRect(bezel.left + u / 2, bezel.top + u / 2, bezel.right - u / 2, bezel.bottom - u / 2, 12.5f * u, 12.5f * u, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xff020202);
        c.drawRoundRect(screen, 3 * u, 3 * u, p);
      }
      if (!slot.isEmpty()) {
        p.setColor(0xff0b0b0a);
        c.drawRoundRect(slot, 9 * u, 9 * u, p);
      }
      if (!grille.isEmpty()) {
        p.setColor(0xff050505);
        for (int row = 0; row < 3; row++)
          for (int col = 0; col < 8; col++)
            c.drawCircle(grille.left + (3 + col * 6.8f) * u, grille.top + (4 + row * 7) * u, 1.5f * u, p);
      }
      if (!thumb.isEmpty()) {
        p.setColor(0xff141513);
        c.drawRoundRect(thumb, 20 * u, 20 * u, p);
        p.setStrokeWidth(Math.max(1, u * .9f));
        for (float x = thumb.left + 10 * u; x < thumb.right - 8 * u; x += 5 * u) {
          p.setColor(0x16ffffff);
          c.drawLine(x, thumb.top + 9 * u, x, thumb.bottom - 9 * u, p);
          p.setColor(0x50000000);
          c.drawLine(x + 1.5f * u, thumb.top + 9 * u, x + 1.5f * u, thumb.bottom - 9 * u, p);
        }
        t.setTextAlign(Paint.Align.RIGHT);
        t.setTextSize(15 * u);
        t.setLetterSpacing(-.04f);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        t.setColor(0xff66635b);
        c.drawText("grain", thumb.right - 20 * u, thumb.bottom - 12 * u, t);
        t.setColor(0xff9c4f2e);
        c.drawText(".", thumb.right - 15 * u, thumb.bottom - 12 * u, t);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setLetterSpacing(.14f);
      }
      c.restore();
    }
  }

  // ───────────────────────────── dials ─────────────────────────────

  /** A knurled drum dial: drag to turn, tap a side to step one click. */
  final class Drum extends View {
    final int key;
    final float pitch;
    String description;
    boolean small;
    private String[] labels = new String[0];
    private boolean[] accent = new boolean[0];
    private int index;
    private float drag, downX, lastX;
    private boolean moved, dragging;
    private ValueAnimator settle;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int slop;

    Drum(Context c, int key, float pitch, String description) {
      super(c);
      this.key = key;
      this.pitch = pitch;
      this.description = description;
      slop = ViewConfiguration.get(c).getScaledTouchSlop();
      t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
      t.setTextAlign(Paint.Align.CENTER);
      setClickable(true);
      setFocusable(true);
    }

    void setItems(String[] labels, boolean[] accent, int index) {
      this.labels = labels;
      this.accent = accent;
      this.index = Math.max(0, Math.min(labels.length - 1, index));
      drag = 0;
      describe();
      invalidate();
    }

    void setIndex(int i) {
      if (dragging || labels.length == 0) return;
      int next = Math.max(0, Math.min(labels.length - 1, i));
      if (next == index) return;
      index = next;
      drag = 0;
      describe();
      invalidate();
    }

    int index() {
      return index;
    }

    private void describe() {
      setContentDescription(description + ": " + (labels.length == 0 ? "" : labels[index]));
    }

    private void changed() {
      performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
      describe();
      host.key(key, index);
    }

    void step(int delta) {
      int next = Math.max(0, Math.min(labels.length - 1, index + delta));
      if (next == index) return;
      if (settle != null) settle.cancel();
      index = next;
      drag = (delta > 0 ? 1 : -1) * pitch * u;
      changed();
      animateTo0();
    }

    private void animateTo0() {
      settle = ValueAnimator.ofFloat(drag, 0);
      settle.setDuration(150);
      settle.addUpdateListener(
          a -> {
            drag = (float) a.getAnimatedValue();
            invalidate();
          });
      settle.start();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
      float pp = pitch * u;
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          if (settle != null) settle.cancel();
          downX = lastX = e.getX();
          moved = false;
          dragging = true;
          getParent().requestDisallowInterceptTouchEvent(true);
          setPressed(true);
          return true;
        case MotionEvent.ACTION_MOVE:
          if (Math.abs(e.getX() - downX) > slop) moved = true;
          if (moved) {
            float dx = e.getX() - lastX;
            boolean atEnd = (index == 0 && drag + dx > 0) || (index == labels.length - 1 && drag + dx < 0);
            drag += atEnd ? dx * .3f : dx;
            while (drag > pp / 2 && index > 0) {
              index--;
              drag -= pp;
              changed();
            }
            while (drag < -pp / 2 && index < labels.length - 1) {
              index++;
              drag += pp;
              changed();
            }
            drag = Math.max(-pp * .45f, Math.min(pp * .45f, drag));
            invalidate();
          }
          lastX = e.getX();
          return true;
        case MotionEvent.ACTION_UP:
          setPressed(false);
          dragging = false;
          if (!moved) {
            performClick();
            step(e.getX() < getWidth() / 2f ? -1 : 1);
          } else animateTo0();
          return true;
        case MotionEvent.ACTION_CANCEL:
          setPressed(false);
          dragging = false;
          animateTo0();
          return true;
        default:
          return super.onTouchEvent(e);
      }
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
      super.onInitializeAccessibilityNodeInfo(info);
      info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
      info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
      if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
        step(1);
        return true;
      }
      if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
        step(-1);
        return true;
      }
      return super.performAccessibilityAction(action, arguments);
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, pp = pitch * u;
      int[] colors = DRUM[finish];
      p.setShader(new LinearGradient(0, 0, 0, h, new int[] {colors[0], colors[1], colors[2]}, new float[] {0, .5f, 1}, Shader.TileMode.CLAMP));
      c.drawRoundRect(0, 0, w, h, 5 * u, 5 * u, p);
      p.setShader(null);
      p.setStrokeWidth(Math.max(1, u * .8f));
      float spacing = 3.4f * u, phase = ((drag % spacing) + spacing) % spacing;
      for (float x = phase; x < w; x += spacing) {
        float a = (x - cx) / cx;
        int alpha = (int) (70 * Math.max(0, 1 - a * a));
        p.setColor(Color.argb(alpha, 0, 0, 0));
        c.drawLine(x, 0, x, h * .2f, p);
        c.drawLine(x, h * .8f, x, h, p);
        p.setColor(Color.argb(alpha / 2, 255, 255, 255));
        c.drawLine(x + u * .8f, 0, x + u * .8f, h * .2f, p);
        c.drawLine(x + u * .8f, h * .8f, x + u * .8f, h, p);
      }
      float radius = (float) (cx / Math.sin(1.25));
      int ink = drumInk(), hot = drumAccent();
      for (int i = Math.max(0, index - 14); i <= Math.min(labels.length - 1, index + 14); i++) {
        float dist = (i - index) * pp + drag;
        double angle = dist / radius;
        if (Math.abs(angle) > 1.32) continue;
        float x = cx + (float) (radius * Math.sin(angle));
        float squeeze = (float) Math.cos(angle);
        boolean centre = i == index;
        float size = (small ? (centre ? 11.5f : 8.5f) : (centre ? 11.5f : 9.6f)) * u;
        t.setTextSize(size);
        t.setFakeBoldText(centre);
        int base = accent.length > i && accent[i] ? hot : ink;
        int alpha = (int) (255 * Math.pow(Math.max(0, squeeze), 1.4));
        t.setColor((base & 0x00ffffff) | (alpha << 24));
        c.save();
        c.scale(Math.max(.05f, squeeze), 1, x, cy);
        c.drawText(labels[i], x, cy + size * .36f, t);
        c.restore();
      }
      p.setShader(
          new LinearGradient(
              0, 0, w, 0, new int[] {0xa0000000, 0x00000000, 0x00000000, 0xa0000000},
              new float[] {0, .22f, .78f, 1}, Shader.TileMode.CLAMP));
      c.drawRoundRect(0, 0, w, h, 5 * u, 5 * u, p);
      p.setShader(null);
    }
  }

  /** The rear command dial: drag sideways (or tap a side) for clicks. */
  final class Command extends View {
    private float phase, acc, downX, lastX;
    private boolean moved;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int slop;

    Command(Context c) {
      super(c);
      slop = ViewConfiguration.get(c).getScaledTouchSlop();
      setClickable(true);
      setFocusable(true);
      setContentDescription("Command dial");
    }

    private void click(int d) {
      performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
      host.key(K_COMMAND, d);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
      float step = 12 * u;
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = lastX = e.getX();
          moved = false;
          acc = 0;
          getParent().requestDisallowInterceptTouchEvent(true);
          return true;
        case MotionEvent.ACTION_MOVE:
          if (Math.abs(e.getX() - downX) > slop) moved = true;
          if (moved) {
            float dx = e.getX() - lastX;
            phase += dx;
            acc += dx;
            while (acc > step) {
              acc -= step;
              click(1);
            }
            while (acc < -step) {
              acc += step;
              click(-1);
            }
            invalidate();
          }
          lastX = e.getX();
          return true;
        case MotionEvent.ACTION_UP:
          if (!moved) {
            performClick();
            click(e.getX() < getWidth() / 2f ? -1 : 1);
            phase += e.getX() < getWidth() / 2f ? -step : step;
            invalidate();
          }
          return true;
        case MotionEvent.ACTION_CANCEL:
          return true;
        default:
          return super.onTouchEvent(e);
      }
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), cx = w / 2;
      p.setShader(new LinearGradient(0, 0, 0, h, new int[] {0xff2b2c29, 0xff121311, 0xff050505}, null, Shader.TileMode.CLAMP));
      c.drawRoundRect(0, 0, w, h, 7 * u, 7 * u, p);
      p.setShader(null);
      float spacing = 4.4f * u, off = ((phase % spacing) + spacing) % spacing;
      p.setStrokeWidth(Math.max(1, 1.3f * u));
      for (float x = off; x < w; x += spacing) {
        float a = (x - cx) / cx;
        float k = Math.max(0, 1 - a * a);
        p.setColor(Color.argb((int) (90 * k), 255, 255, 255));
        c.drawLine(x, 3 * u, x, h - 3 * u, p);
        p.setColor(Color.argb((int) (160 * k), 0, 0, 0));
        c.drawLine(x + 1.5f * u, 3 * u, x + 1.5f * u, h - 3 * u, p);
      }
      p.setShader(
          new LinearGradient(
              0, 0, w, 0, new int[] {0xe0000000, 0x00000000, 0x00000000, 0xe0000000},
              new float[] {0, .25f, .75f, 1}, Shader.TileMode.CLAMP));
      c.drawRoundRect(0, 0, w, h, 7 * u, 7 * u, p);
      p.setShader(null);
    }
  }

  /**
   * Analog Fujifilm focus lever: continuous 360-degree VSync AF gliding in camera mode, instant
   * directional step + hold-to-repeat in menus, center press, and damped mechanical spring return.
   */
  final class Lever extends View {
    private static final float DEADZONE = 0.13f;
    private static final float CARDINAL_THRESHOLD = 0.32f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private float originX, originY;
    private float visX, visY;
    private float velX, velY, peakVx, peakVy;
    private boolean touching, centerHeld, glided, engagedHaptic, glideRunning;
    private int cardinalDir = -1, repeatSteps, glideFrames;
    private long downAt, lastFrameNs;
    private ValueAnimator springAnim;

    private final Choreographer.FrameCallback glideCallback =
        new Choreographer.FrameCallback() {
          @Override
          public void doFrame(long frameTimeNanos) {
            if (!touching || !host.leverAnalogMode()) {
              glideRunning = false;
              lastFrameNs = 0;
              return;
            }
            float dt =
                lastFrameNs == 0
                    ? (1f / 60f)
                    : Math.min(0.05f, Math.max(0.001f, (frameTimeNanos - lastFrameNs) * 1e-9f));
            lastFrameNs = frameTimeNanos;
            if (Math.hypot(velX, velY) > 1e-4f) {
              glideFrames++;
              host.leverGlide(velX, velY, dt, false);
              Choreographer.getInstance().postFrameCallback(this);
            } else {
              glideRunning = false;
              lastFrameNs = 0;
            }
          }
        };

    private final Runnable repeatTask =
        new Runnable() {
          @Override
          public void run() {
            if (!touching || cardinalDir <= 0 || host.leverAnalogMode()) return;
            repeatSteps++;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            host.key(K_LEVER, cardinalDir);
            long delay = repeatSteps >= 6 ? 75L : 108L;
            postDelayed(this, delay);
          }
        };

    Lever(Context c) {
      super(c);
      setClickable(true);
      setFocusable(true);
      setContentDescription("Focus lever: drag smoothly to move focus or tilt to navigate menus");
    }

    private void stopLoops() {
      if (glideRunning) {
        Choreographer.getInstance().removeFrameCallback(glideCallback);
        glideRunning = false;
      }
      lastFrameNs = 0;
      removeCallbacks(repeatTask);
    }

    private void cancelSpring() {
      if (springAnim != null) {
        springAnim.cancel();
        springAnim = null;
      }
    }

    private void springBack() {
      cancelSpring();
      final float sx = visX, sy = visY;
      if (Math.hypot(sx, sy) < 0.01f) {
        visX = 0f;
        visY = 0f;
        invalidate();
        return;
      }
      springAnim = ValueAnimator.ofFloat(1f, 0f);
      springAnim.setDuration(145L);
      springAnim.setInterpolator(new OvershootInterpolator(1.35f));
      springAnim.addUpdateListener(
          a -> {
            float k = (float) a.getAnimatedValue();
            visX = sx * k;
            visY = sy * k;
            invalidate();
          });
      springAnim.start();
    }

    private void startGlideLoop() {
      if (!glideRunning) {
        glideRunning = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(glideCallback);
      }
    }

    private void updateStick(float tx, float ty) {
      float r = Math.min(getWidth(), getHeight()) * 0.5f;
      float maxTravel = Math.max(8f, r * 0.45f);
      float[] leashed =
          FocusMath.leverFloatingOrigin(originX, originY, tx, ty, maxTravel * 1.30f);
      originX = leashed[0];
      originY = leashed[1];
      float[] resp = FocusMath.leverResponse(tx - originX, ty - originY, maxTravel, DEADZONE);
      velX = resp[0];
      velY = resp[1];
      visX = resp[2];
      visY = resp[3];
      float speed = resp[4];
      if (speed > Math.hypot(peakVx, peakVy)) {
        peakVx = velX;
        peakVy = velY;
      }
      if (speed > 0f) centerHeld = false;

      if (host.leverAnalogMode()) {
        removeCallbacks(repeatTask);
        cardinalDir = -1;
        if (speed > 0f) {
          if (!engagedHaptic) {
            engagedHaptic = true;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
          }
          glided = true;
          startGlideLoop();
        }
      } else {
        int dir = FocusMath.leverCardinal(visX, visY, CARDINAL_THRESHOLD, cardinalDir);
        if (dir != cardinalDir) {
          removeCallbacks(repeatTask);
          cardinalDir = dir;
          if (dir > 0) {
            glided = true;
            repeatSteps = 0;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            host.key(K_LEVER, dir);
            postDelayed(repeatTask, 260L);
          }
        }
      }
      invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: {
          cancelSpring();
          stopLoops();
          if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
          touching = true;
          downAt = SystemClock.uptimeMillis();
          glided = false;
          engagedHaptic = false;
          cardinalDir = -1;
          repeatSteps = 0;
          glideFrames = 0;
          velX = velY = peakVx = peakVy = 0f;

          float cx = getWidth() * 0.5f, cy = getHeight() * 0.5f;
          float r = Math.min(cx, cy);
          float d0 = (float) Math.hypot(e.getX() - cx, e.getY() - cy);
          if (d0 <= r * 0.57f) {
            originX = e.getX();
            originY = e.getY();
            centerHeld = true;
            visX = (e.getX() - cx) / Math.max(1f, r) * 0.14f;
            visY = (e.getY() - cy) / Math.max(1f, r) * 0.14f;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            invalidate();
          } else {
            originX = cx;
            originY = cy;
            centerHeld = false;
            updateStick(e.getX(), e.getY());
          }
          return true;
        }
        case MotionEvent.ACTION_MOVE:
          if (touching) updateStick(e.getX(), e.getY());
          return true;
        case MotionEvent.ACTION_UP: {
          boolean wasTouching = touching;
          touching = false;
          stopLoops();
          long heldMs = SystemClock.uptimeMillis() - downAt;
          if (wasTouching) {
            if (host.leverAnalogMode()) {
              if (glided) {
                double pk = Math.hypot(peakVx, peakVy);
                if (heldMs < 200L && glideFrames <= 2 && pk > 1e-4) {
                  float ux = (float) (peakVx / pk), uy = (float) (peakVy / pk);
                  host.leverGlide(ux, uy, 0.068f, true);
                } else {
                  host.leverGlide(0f, 0f, 0f, true);
                }
              } else {
                performClick();
                host.key(K_LEVER, LEVER_CENTER);
              }
            } else if (!glided) {
              performClick();
              host.key(K_LEVER, LEVER_CENTER);
            }
          }
          velX = velY = peakVx = peakVy = 0f;
          centerHeld = false;
          cardinalDir = -1;
          springBack();
          return true;
        }
        case MotionEvent.ACTION_CANCEL:
          if (touching && glided && host.leverAnalogMode()) {
            host.leverGlide(0f, 0f, 0f, true);
          }
          touching = false;
          stopLoops();
          velX = velY = peakVx = peakVy = 0f;
          centerHeld = false;
          cardinalDir = -1;
          springBack();
          return true;
        default:
          return true;
      }
    }

    @Override
    protected void onDetachedFromWindow() {
      touching = false;
      stopLoops();
      cancelSpring();
      super.onDetachedFromWindow();
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }

    private int blendArrowColor(float align) {
      float t = Math.max(0f, Math.min(1f, (align - 0.18f) / 0.65f));
      int r0 = 0x98, g0 = 0x94, b0 = 0x8a;
      int r1 = (ORANGE >> 16) & 0xff, g1 = (ORANGE >> 8) & 0xff, b1 = ORANGE & 0xff;
      return Color.rgb(
          Math.round(r0 + (r1 - r0) * t),
          Math.round(g0 + (g1 - g0) * t),
          Math.round(b0 + (b1 - b0) * t));
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight();
      float r = Math.min(w, h) * 0.5f, cx = w * 0.5f, cy = h * 0.5f;
      if (r <= 0f) return;

      // 1. Anodized outer housing bezel
      p.setStyle(Paint.Style.FILL);
      p.setShader(
          new RadialGradient(
              cx - r * 0.25f,
              cy - r * 0.32f,
              r * 1.25f,
              0xff3d3e39,
              0xff0b0c0a,
              Shader.TileMode.CLAMP));
      c.drawCircle(cx, cy, r, p);
      p.setShader(null);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(1f, 0.9f * u));
      p.setColor(0x28ffffff);
      c.drawCircle(cx, cy, r - 0.5f * u, p);

      // 2. Recessed gimbal socket well
      p.setStyle(Paint.Style.FILL);
      p.setColor(0xff050505);
      c.drawCircle(cx, cy, r * 0.68f, p);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(1f, 1.1f * u));
      p.setColor(0x88000000);
      c.drawCircle(cx, cy, r * 0.67f, p);

      // 3. Directional chevrons with warm active illumination
      p.setStyle(Paint.Style.FILL);
      float tr = r * 0.83f, s = 2.8f * u;
      float[] aligns = {-visY, visX, visY, -visX};
      for (int d = 0; d < 4; d++) {
        p.setColor(blendArrowColor(aligns[d]));
        c.save();
        c.rotate(d * 90f, cx, cy);
        tri.reset();
        tri.moveTo(cx, cy - tr - s * 0.95f);
        tri.lineTo(cx - s, cy - tr + s * 0.65f);
        tri.lineTo(cx + s, cy - tr + s * 0.65f);
        tri.close();
        c.drawPath(tri, p);
        c.restore();
      }

      // 4. Analog 360-degree stem + floating cap shadow
      float maxShift = r * 0.28f;
      float nx = cx + visX * maxShift, ny = cy + visY * maxShift;
      float nr = r * (centerHeld ? 0.49f : 0.54f);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(nr * 0.68f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setColor(0xff121311);
      c.drawLine(cx, cy, nx, ny, p);

      p.setStyle(Paint.Style.FILL);
      p.setColor(0x68000000);
      c.drawCircle(nx + 1.0f * u, ny + 1.8f * u, nr * 1.03f, p);

      // 5. Knurled Fujifilm X-T5 thumb cap with concave dish
      p.setShader(
          new RadialGradient(
              nx - nr * 0.32f,
              ny - nr * 0.38f,
              nr * 1.45f,
              0xff686a63,
              0xff161715,
              Shader.TileMode.CLAMP));
      c.drawCircle(nx, ny, nr, p);
      p.setShader(null);

      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(1f, 0.8f * u));
      p.setColor(0x36ffffff);
      c.drawCircle(nx, ny, nr - 0.4f * u, p);

      p.setStyle(Paint.Style.FILL);
      float dishR = nr * 0.72f;
      p.setShader(
          new RadialGradient(
              nx + dishR * 0.22f,
              ny + dishR * 0.26f,
              dishR * 1.3f,
              0xff3c3e39,
              0xff181917,
              Shader.TileMode.CLAMP));
      c.drawCircle(nx, ny, dishR, p);
      p.setShader(null);

      // Micro-knurling grip studs (inner 6 + outer 12)
      for (int ring = 0; ring < 2; ring++) {
        int count = ring == 0 ? 6 : 12;
        float kr = nr * (ring == 0 ? 0.34f : 0.62f);
        float dotR = (ring == 0 ? 0.88f : 0.78f) * u;
        double phase = ring == 0 ? 0.0 : Math.PI / 12.0;
        for (int i = 0; i < count; i++) {
          double a = phase + i * (2.0 * Math.PI / count);
          float kx = nx + (float) Math.cos(a) * kr;
          float ky = ny + (float) Math.sin(a) * kr;
          p.setColor(0x66000000);
          c.drawCircle(kx + 0.35f * u, ky + 0.45f * u, dotR, p);
          p.setColor(0x28ffffff);
          c.drawCircle(kx - 0.25f * u, ky - 0.25f * u, dotR * 0.78f, p);
        }
      }
    }
  }

  /**
   * The shutter button. Press and hold to focus (half-press), release to shoot. Extends TextView
   * so the activity can show the self-timer count on it ("3", "2", …; "·" while capturing).
   */
  final class Shutter extends TextView {
    private boolean half;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);

    Shutter(Context c) {
      super(c);
      setText("◉");
      setClickable(true);
      setFocusable(true);
      setContentDescription("Take photo");
      setOnClickListener(v -> host.key(K_SHUTTER, 0));
      t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
      t.setTextAlign(Paint.Align.CENTER);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          half = true;
          performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
          invalidate();
          host.key(K_SHUTTER, 1);
          return true;
        case MotionEvent.ACTION_UP:
          boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight();
          half = false;
          invalidate();
          if (inside) performClick();
          else host.key(K_SHUTTER, -1);
          return true;
        case MotionEvent.ACTION_CANCEL:
          half = false;
          invalidate();
          host.key(K_SHUTTER, -1);
          return true;
        default:
          return true;
      }
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), r = Math.min(w, getHeight()) / 2f, cx = w / 2f, cy = getHeight() / 2f;
      if (evf) {
        p.setColor(0x80000000);
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * u);
        p.setColor(0xddf2efe6);
        c.drawCircle(cx, cy, r - 2 * u, p);
        p.setStyle(Paint.Style.FILL);
      } else {
        p.setShader(
            new SweepGradient(
                cx, cy, new int[] {0xfff7f7f3, 0xff96958f, 0xffeceae4, 0xff7d7c77, 0xffdedcd6, 0xff8f8e88, 0xfff7f7f3},
                null));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        p.setColor(0xff111110);
        c.drawCircle(cx, cy, r * .79f, p);
      }
      String label = String.valueOf(getText());
      boolean busy = "·".equals(label);
      float br = r * (half ? .58f : .63f);
      p.setShader(
          new RadialGradient(
              cx - br * .3f, cy - br * .35f, br * 1.3f,
              busy ? new int[] {0xff8a7a70, 0xff4a3c33} : half ? new int[] {0xffe8743f, 0xffa83c18} : new int[] {0xffff8d55, 0xffc4441c},
              null, Shader.TileMode.CLAMP));
      c.drawCircle(cx, cy, br, p);
      p.setShader(null);
      p.setColor(0x26000000);
      for (int ring = 1; ring <= 3; ring++)
        for (int i = 0; i < ring * 8; i++) {
          double a = i * 2 * Math.PI / (ring * 8);
          float rr = br * ring / 4.2f;
          c.drawCircle(cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr, .8f * u, p);
        }
      if (!label.isEmpty() && Character.isDigit(label.charAt(0))) {
        t.setColor(0xffffffff);
        t.setTextSize(br * .95f);
        c.drawText(label, cx, cy + br * .34f, t);
      }
    }
  }

  final class Eyepiece extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Eyepiece(Context c) {
      super(c);
      setClickable(true);
      setFocusable(true);
      setContentDescription("Viewfinder: eye-level view");
      setOnClickListener(
          v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            host.key(K_EYE, 0);
          });
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight();
      p.setShader(new LinearGradient(0, 0, 0, h, 0xff34352f, 0xff0c0d0b, Shader.TileMode.CLAMP));
      c.drawRoundRect(0, 0, w, h, 11 * u, 11 * u, p);
      p.setShader(null);
      p.setColor(0xff050505);
      c.drawRoundRect(5 * u, 5 * u, w - 5 * u, h - 5 * u, 8 * u, 8 * u, p);
      p.setShader(new LinearGradient(0, 9 * u, w, h - 9 * u, new int[] {0xff31465a, 0xff0d141b, 0xff1c2a36}, null, Shader.TileMode.CLAMP));
      c.drawRoundRect(10 * u, 10 * u, w - 10 * u, h - 10 * u, 5 * u, 5 * u, p);
      p.setShader(null);
      p.setColor(0x30ffffff);
      Path glare = new Path();
      glare.moveTo(14 * u, 12 * u);
      glare.lineTo(w * .45f, 12 * u);
      glare.lineTo(w * .3f, h - 12 * u);
      glare.lineTo(14 * u, h - 12 * u);
      glare.close();
      c.drawPath(glare, p);
      if (isPressed()) {
        p.setColor(0x40000000);
        c.drawRoundRect(0, 0, w, h, 11 * u, 11 * u, p);
      }
    }

    @Override
    protected void drawableStateChanged() {
      super.drawableStateChanged();
      invalidate();
    }
  }

  /** Tiny indicator lamp beside the eyepiece: green = focus confirmed, orange = timer/working. */
  final class Lamp extends View {
    private int mode;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Lamp(Context c) {
      super(c);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 0 off, 1 green, 2 orange, 3 orange blinking. */
    void set(int value) {
      if (mode == value) return;
      mode = value;
      invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
      float r = Math.min(getWidth(), getHeight()) / 2f;
      boolean lit = mode == 1 || mode == 2 || (mode == 3 && (SystemClock.uptimeMillis() / 250) % 2 == 0);
      p.setColor(!lit ? 0xff2e2a26 : mode == 1 ? GREEN : 0xffff7a3d);
      c.drawCircle(r, r, r, p);
      if (mode == 3) postInvalidateDelayed(250);
    }
  }

  final class Power extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Power(Context c) {
      super(c);
      setClickable(true);
      setFocusable(true);
      setContentDescription("On / off");
      setOnClickListener(
          v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            host.key(K_POWER, on ? 0 : 1);
          });
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), th = h * .62f, top = (h - th) / 2;
      p.setColor(finish == 0 ? 0xff1d1e1b : 0xff0a0a09);
      c.drawRoundRect(0, top, w, top + th, th / 2, th / 2, p);
      float kw = w * .42f, kx = on ? w - kw : 0;
      p.setShader(new LinearGradient(0, 0, 0, h, 0xfffafaf6, 0xffb4b3ad, Shader.TileMode.CLAMP));
      c.drawRoundRect(kx, 0, kx + kw, h, 5 * u, 5 * u, p);
      p.setShader(null);
      p.setColor(on ? ORANGE : 0xff8d8b85);
      c.drawRect(kx + kw / 2 - .9f * u, h * .25f, kx + kw / 2 + .9f * u, h * .75f, p);
    }
  }

  /** The memo holder: the end of the loaded film's box. Tap for details, hold to compare. */
  final class Memo extends View {
    private String name = "", code = "", kind = "";
    private int bg = 0xffd9a33f, fg = 0xff2b2418, stripe = 0xff2f5c8a;
    private boolean holding;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable hold =
        () -> {
          holding = true;
          performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
          host.key(K_MEMO, 1);
        };

    Memo(Context c) {
      super(c);
      setClickable(true);
      setFocusable(true);
    }

    void set(String name, String code, String kind, int bg, int fg, int stripe) {
      this.name = name;
      this.code = code;
      this.kind = kind;
      this.bg = bg;
      this.fg = fg;
      this.stripe = stripe;
      setContentDescription("Memo holder: " + name + ". Tap for details, hold to see the phone’s view.");
      invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          holding = false;
          postDelayed(hold, 420);
          return true;
        case MotionEvent.ACTION_UP:
          removeCallbacks(hold);
          if (holding) host.key(K_MEMO, 2);
          else performClick();
          holding = false;
          return true;
        case MotionEvent.ACTION_CANCEL:
          removeCallbacks(hold);
          if (holding) host.key(K_MEMO, 2);
          holding = false;
          return true;
        default:
          return true;
      }
    }

    @Override
    public boolean performClick() {
      super.performClick();
      host.key(K_MEMO, 0);
      return true;
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), in = 6 * u;
      p.setColor(0xff0c0d0b);
      c.drawRoundRect(0, 0, w, h, 6 * u, 6 * u, p);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(1, u));
      p.setColor(0x30ffffff);
      c.drawRoundRect(u / 2, u / 2, w - u / 2, h - u / 2, 6 * u, 6 * u, p);
      p.setStyle(Paint.Style.FILL);
      RectF win = new RectF(in, in, w - in, h - in);
      p.setColor(bg);
      c.drawRoundRect(win, 2 * u, 2 * u, p);
      t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
      t.setTextAlign(Paint.Align.LEFT);
      t.setLetterSpacing(.15f);
      t.setTextSize(4.6f * u);
      t.setColor((fg & 0x00ffffff) | 0xb0000000);
      c.drawText("GRAIN", win.left + 3 * u, win.top + 7 * u, t);
      c.drawText("FILM", win.left + 3 * u, win.top + 12 * u, t);
      float stripeTop = win.bottom - (landscape ? 82 : 46) * u;
      p.setColor(stripe);
      float sw = (win.width() - 8 * u) / 2;
      c.drawRect(win.left + 3 * u, stripeTop, win.left + 3 * u + sw * 1.25f, stripeTop + 6 * u, p);
      c.drawRect(win.left + 6 * u + sw * 1.25f, stripeTop, win.right - 3 * u, stripeTop + 6 * u, p);
      t.setTextAlign(Paint.Align.CENTER);
      t.setTextSize(5.6f * u);
      t.setColor(fg);
      c.drawText(code, win.centerX(), win.bottom - 13 * u, t);
      c.drawText(kind, win.centerX(), win.bottom - 5 * u, t);
      float nameTop = win.top + 16 * u, nameBottom = stripeTop - 4 * u, span = nameBottom - nameTop;
      t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
      t.setLetterSpacing(0);
      float size = (landscape ? 15 : 12.5f) * u;
      t.setTextSize(size);
      float tw = t.measureText(name);
      if (tw > span && tw > 0) {
        size *= span / tw;
        t.setTextSize(size);
      }
      c.save();
      c.rotate(-90, win.centerX(), (nameTop + nameBottom) / 2);
      c.drawText(name, win.centerX(), (nameTop + nameBottom) / 2 + size * .35f, t);
      c.restore();
    }
  }

  // ───────────────────────────── the screen ─────────────────────────────

  final class Osd extends View {
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF img = new RectF();
    private final Path path = new Path();
    private final ScaleGestureDetector scale;
    private float downX, downY, holdX, holdY;
    private boolean multi, holding;
    private long evfFocusAt;
    private final Runnable holdTask;

    Osd(Context c) {
      super(c);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
      scale =
          new ScaleGestureDetector(
              c,
              new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                  host.pinch(d.getScaleFactor());
                  return true;
                }
              });
      holdTask =
          () -> {
            if (!multi && !scale.isInProgress()) {
              holding = true;
              performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
              host.lcdHold(holdX, holdY, true);
            }
          };
    }

    private void syncImg() {
      img.set(image.left - getLeft(), image.top - getTop(), image.right - getLeft(), image.bottom - getTop());
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
      syncImg();
      scale.onTouchEvent(e);
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = e.getX();
          downY = e.getY();
          multi = false;
          holding = false;
          if (img.contains(downX, downY) && img.width() > 0 && img.height() > 0) {
            holdX = (downX - img.left) / img.width();
            holdY = (downY - img.top) / img.height();
            postDelayed(holdTask, 340);
          }
          return true;
        case MotionEvent.ACTION_POINTER_DOWN:
          multi = true;
          removeCallbacks(holdTask);
          if (holding) {
            holding = false;
            host.lcdHold(holdX, holdY, false);
          }
          return true;
        case MotionEvent.ACTION_MOVE:
          if (!holding && Math.hypot(e.getX() - downX, e.getY() - downY) >= 16 * density) {
            removeCallbacks(holdTask);
          } else if (holding && (multi || scale.isInProgress())) {
            holding = false;
            host.lcdHold(holdX, holdY, false);
          }
          return true;
        case MotionEvent.ACTION_UP:
          removeCallbacks(holdTask);
          if (holding) {
            holding = false;
            host.lcdHold(holdX, holdY, false);
            return true;
          }
          float dx = e.getX() - downX, dy = e.getY() - downY;
          if (multi || scale.isInProgress()) return true;
          if (Math.hypot(dx, dy) < 16 * density) {
            if (img.contains(e.getX(), e.getY()) && img.width() > 0) {
              if (evf) {
                evfFocusAt = SystemClock.uptimeMillis();
                invalidate();
                postInvalidateDelayed(1650);
              }
              host.lcdTap(
                  e.getRawX(), e.getRawY(), (e.getX() - img.left) / img.width(), (e.getY() - img.top) / img.height());
            } else if (evf) {
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
              host.key(K_EVF_BACK, 0);
            }
          } else if (Math.abs(dx) > 60 * density && Math.abs(dx) > 2 * Math.abs(dy))
            host.key(K_SWIPE, dx < 0 ? 1 : -1);
          return true;
        case MotionEvent.ACTION_CANCEL:
          removeCallbacks(holdTask);
          if (holding) {
            holding = false;
            host.lcdHold(holdX, holdY, false);
          }
          return true;
        default:
          return true;
      }
    }

    private float text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
      if (s == null || s.isEmpty()) return 0;
      t.setTextSize(size);
      t.setColor(color);
      t.setTextAlign(align);
      t.setShadowLayer(1.6f * u, 0, .6f * u, 0xb0000000);
      c.drawText(s, x, y, t);
      return t.measureText(s);
    }

    /** Left-aligned info tag at a baseline, optionally boxed; returns its width. */
    private float tag(Canvas c, String s, float x, float y, float size, boolean boxed, int color) {
      if (s == null || s.isEmpty()) return 0;
      t.setTextSize(size);
      float tw = t.measureText(s);
      if (boxed) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, .8f * u));
        p.setColor((color & 0x00ffffff) | 0xd0000000);
        p.setShadowLayer(1.2f * u, 0, 0, 0x90000000);
        c.drawRoundRect(x, y - size * .98f, x + tw + 6 * u, y + size * .3f, 2 * u, 2 * u, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
        text(c, s, x + 3 * u, y, size, color, Paint.Align.LEFT);
        return tw + 6 * u;
      }
      return text(c, s, x, y, size, color, Paint.Align.LEFT);
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight();
      syncImg();
      OsdState o = state;
      if (!on) {
        p.setColor(0xff000000);
        c.drawRect(0, 0, w, h, p);
        return;
      }
      long now = SystemClock.uptimeMillis();
      if (evf) {
        if (img.width() <= 0 || img.height() <= 0) return;
        c.save();
        path.reset();
        path.addRoundRect(img, 7 * u, 7 * u, Path.Direction.CW);
        c.clipPath(path);
        if (o.peak != null) {
          c.drawBitmap(o.peak, null, img, null);
        }
        boolean tapAf = now - evfFocusAt < 1600;
        if (tapAf || o.afState != 0) {
          float ax = o.afArea == GrainCamera.AF_CENTER ? img.centerX() : img.left + o.afX * img.width();
          float ay = o.afArea == GrainCamera.AF_CENTER ? img.centerY() : img.top + o.afY * img.height();
          float box = Math.max(10 * u, Math.min(img.width(), img.height()) * .18f);
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(Math.max(1, 1.2f * u));
          p.setColor(o.afState == 3 ? 0xffff5a3c : GREEN);
          c.drawRect(ax - box / 2f, ay - box / 2f, ax + box / 2f, ay + box / 2f, p);
          p.setStrokeWidth(Math.max(1, .9f * u));
          c.drawLine(ax - 2.4f * u, ay, ax + 2.4f * u, ay, p);
          c.drawLine(ax, ay - 2.4f * u, ax, ay + 2.4f * u, p);
          p.setStyle(Paint.Style.FILL);
        }
        if (o.mag != null) {
          c.drawBitmap(o.mag, null, img, null);
        }
        if (!o.countdown.isEmpty()) {
          text(c, o.countdown, img.centerX(), img.centerY() + 9 * u, 24 * u, 0xffff8d55, Paint.Align.CENTER);
        }
        if (o.busy) {
          float run = (now % 1400) / 1400f, seg = img.width() * .28f;
          float sx = img.left - seg + (img.width() + seg) * run;
          p.setColor(ORANGE);
          c.drawRect(Math.max(img.left, sx), img.bottom - 1.8f * u, Math.min(img.right, sx + seg), img.bottom, p);
          postInvalidateDelayed(40);
        }
        c.restore();
        return;
      }
      float k = 1, fs = 8.4f * u, pad = 7 * u;
      int white = 0xfff4f1e8;
      if (o.grid && img.width() > 0) {
        p.setColor(0x60ffffff);
        p.setStrokeWidth(Math.max(1, .8f * u));
        for (int n = 1; n < 3; n++) {
          c.drawLine(img.left + img.width() * n / 3, img.top, img.left + img.width() * n / 3, img.bottom, p);
          c.drawLine(img.left, img.top + img.height() * n / 3, img.right, img.top + img.height() * n / 3, p);
        }
      }
      float ax = img.left + o.afX * img.width(), ay = img.top + o.afY * img.height(), as = 16 * u * k;
      if (o.showLevel && !Float.isNaN(o.level) && !o.infoOff) {
        boolean level = Math.abs(o.level) < 1;
        float len = Math.min(img.width(), img.height()) * .32f, gap = as + 6 * u;
        c.save();
        c.rotate(-o.level, img.centerX(), img.centerY());
        p.setStrokeWidth(Math.max(1, 1.2f * u));
        p.setColor(level ? GREEN : 0xd8ffffff);
        p.setShadowLayer(1.2f * u, 0, 0, 0x90000000);
        c.drawLine(img.centerX() - len, img.centerY(), img.centerX() - gap, img.centerY(), p);
        c.drawLine(img.centerX() + gap, img.centerY(), img.centerX() + len, img.centerY(), p);
        p.clearShadowLayer();
        c.restore();
      }
      if (o.afArea == 1 && img.width() > 0) {
        // MULTI: the grid the lens searches, drawn where the AF region actually is.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, .9f * u));
        p.setColor(0xb0ffffff);
        for (int n = 1; n < 3; n++) {
          c.drawLine(img.left + img.width() * n / 3, img.top, img.left + img.width() * n / 3,
              img.bottom, p);
          c.drawLine(img.left, img.top + img.height() * n / 3, img.right, img.top + img.height() * n / 3,
              p);
        }
      }
      if (o.peak != null && img.width() > 0) {
        c.drawBitmap(o.peak, null, img, null);
      }
      int afColor = o.afState == 2 ? GREEN : o.afState == 3 ? 0xffff5a3c : 0xf0ffffff;
      if (o.afArea == GrainCamera.AF_FACE && o.faceActive && img.width() > 0) {
        float fl = img.left + o.faceL * img.width(), ft = img.top + o.faceT * img.height();
        float fr = img.left + o.faceR * img.width(), fb = img.top + o.faceB * img.height();
        float corner = Math.max(6 * u, Math.min(fr - fl, fb - ft) * .24f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, 1.6f * u));
        p.setColor(afColor);
        p.setShadowLayer(1.2f * u, 0, 0, 0x90000000);
        c.drawLine(fl, ft, fl + corner, ft, p);
        c.drawLine(fl, ft, fl, ft + corner, p);
        c.drawLine(fr - corner, ft, fr, ft, p);
        c.drawLine(fr, ft, fr, ft + corner, p);
        c.drawLine(fl, fb - corner, fl, fb, p);
        c.drawLine(fl, fb, fl + corner, fb, p);
        c.drawLine(fr - corner, fb, fr, fb, p);
        c.drawLine(fr, fb - corner, fr, fb, p);
        float fcx = (fl + fr) * .5f, fcy = (ft + fb) * .5f;
        p.setStrokeWidth(Math.max(1, u));
        c.drawLine(fcx - 2.5f * u, fcy, fcx + 2.5f * u, fcy, p);
        c.drawLine(fcx, fcy - 2.5f * u, fcx, fcy + 2.5f * u, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
      } else {
        if (o.afArea == GrainCamera.AF_CENTER) {
          ax = img.centerX();
          ay = img.centerY();
        }
        if (o.afArea == 2) {
          float zs = Math.min(img.width(), img.height()) * .25f;
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(Math.max(1, .9f * u));
          p.setColor(0xb0ffffff);
          c.drawRect(ax - zs, ay - zs, ax + zs, ay + zs, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, 1.4f * u));
        p.setColor(afColor);
        p.setShadowLayer(1.2f * u, 0, 0, 0x90000000);
        c.drawRect(ax - as, ay - as, ax + as, ay + as, p);
        p.setStrokeWidth(Math.max(1, u));
        c.drawLine(ax - 3 * u, ay, ax + 3 * u, ay, p);
        c.drawLine(ax, ay - 3 * u, ax, ay + 3 * u, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
      }
      if (o.mag != null && img.width() > 0 && img.height() > 0) {
        float mx = o.afArea == GrainCamera.AF_FACE && o.faceActive ? (o.faceL + o.faceR) * .5f : o.afX;
        float my = o.afArea == GrainCamera.AF_FACE && o.faceActive ? (o.faceT + o.faceB) * .5f : o.afY;
        if (o.afArea == GrainCamera.AF_CENTER) {
          mx = .5f;
          my = .5f;
        }
        float[] mb = FocusMath.magnifierBox(img.left, img.top, img.width(), img.height(), mx, my, 6 * u);
        RectF mr = new RectF(mb[0], mb[1], mb[2], mb[3]);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xb8000000);
        c.drawRoundRect(
            mr.left - 2.2f * u, mr.top - 2.2f * u, mr.right + 2.2f * u, mr.bottom + 2.2f * u,
            4 * u, 4 * u, p);
        c.save();
        c.clipRect(mr);
        c.drawBitmap(o.mag, null, mr, null);
        c.restore();
        float mcx = mr.centerX(), mcy = mr.centerY();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, .9f * u));
        p.setColor(0xd8ffffff);
        c.drawLine(mcx - 11 * u, mcy, mcx - 3 * u, mcy, p);
        c.drawLine(mcx + 3 * u, mcy, mcx + 11 * u, mcy, p);
        c.drawLine(mcx, mcy - 11 * u, mcx, mcy - 3 * u, p);
        c.drawLine(mcx, mcy + 3 * u, mcx, mcy + 11 * u, p);
        p.setStrokeWidth(Math.max(1, 1.3f * u));
        p.setColor(afColor);
        c.drawRoundRect(mr, 2.5f * u, 2.5f * u, p);
        p.setStyle(Paint.Style.FILL);
        if (o.magLabel != null && !o.magLabel.isEmpty()) {
          tag(c, o.magLabel, mr.left + 4 * u, mr.top + 4 * u + fs * .88f, fs * .82f, true, white);
        }
      }
      if (!o.infoOff) {
        boolean bandTop = img.top >= 22 * u;
        float yTop = bandTop ? img.top - 8 * u : img.top + pad + fs;
        float x = img.left + pad;
        x += tag(c, o.code, x, yTop, fs, true, white) + 5 * u;
        text(c, o.film, x, yTop, fs, white, Paint.Align.LEFT);
        float xr = img.right - pad;
        xr -= battery(c, xr, yTop, fs, o.battery) + 6 * u;
        xr -= text(c, o.frames, xr, yTop, fs, white, Paint.Align.RIGHT) + 7 * u;
        xr -= text(c, o.ratio, xr, yTop, fs, white, Paint.Align.RIGHT) + 7 * u;
        text(c, o.format, xr, yTop, fs, white, Paint.Align.RIGHT);
        boolean sideLeft = img.left >= 90 * u;
        float ix = sideLeft ? img.left - 82 * u : img.left + pad;
        float iy = bandTop ? img.top + pad + fs : yTop + fs * 2.1f;
        for (String icon : o.icons) {
          tag(c, icon, ix, iy, fs * .92f, true, white);
          iy += fs * 1.85f;
        }
        float hw = 66 * u * k, hh = 34 * u * k;
        boolean sideRight = (w - img.right) >= hw + 16 * u;
        float hx = sideRight ? img.right + 12 * u : img.right - pad - hw;
        float hy = bandTop ? img.top + pad : yTop + fs * .9f;
        if (o.showHistogram && o.histogram != null) histogram(c, hx, hy, hw, hh, o.histogram);
        float ty = hy + (o.showHistogram && o.histogram != null ? hh + fs * 1.5f : fs);
        if (o.aeLock) {
          tagRight(c, "AE-L", hx + hw, ty, fs, ORANGE);
          ty += fs * 1.8f;
        }
        if (!o.drive.isEmpty()) {
          tagRight(c, o.drive, hx + hw, ty, fs, white);
          ty += fs * 1.8f;
        }
        if (!o.zoom.isEmpty()) {
          tagRight(c, o.zoom, hx + hw, ty, fs, white);
          ty += fs * 1.8f;
        }
        if (o.updateAvailable) {
          tagRight(c, "UPDATE", hx + hw, ty, fs, ORANGE);
        }
        boolean bandBottom = (h - img.bottom) >= 22 * u;
        float yb = bandBottom ? img.bottom + 15 * u : img.bottom - pad - u;
        float bx = img.left + pad;
        bx += modeBox(c, o.mode, bx, yb, fs) + 6 * u;
        int valueColor = o.manual ? 0xffffd27a : white;
        bx += text(c, o.shutter, bx, yb, fs * 1.1f, valueColor, Paint.Align.LEFT) + 8 * u;
        bx += text(c, o.aperture, bx, yb, fs * 1.1f, white, Paint.Align.LEFT) + 8 * u;
        float isoRight = img.right - pad;
        float isoWidth = iso(c, isoRight, yb, fs, o.iso, o.isoAuto, valueColor);
        scaleBar(c, bx, isoRight - isoWidth - 10 * u, yb, fs, o.exposure, o.exposureWarn, now);
      }
      if (!o.countdown.isEmpty()) {
        text(c, o.countdown, img.centerX(), img.centerY() + 22 * u, 64 * u, 0xffff8d55, Paint.Align.CENTER);
      }
      if (o.toastTitle != null && now - o.toastAt < 1600) {
        float alpha = Math.min(1, (1600 - (now - o.toastAt)) / 300f);
        toast(c, o.toastTitle, o.toastSub, img.centerX(), img.centerY(), fs, alpha);
        postInvalidateDelayed(40);
      }
      if (o.busy) {
        float bh = 22 * u;
        p.setColor(0xc0000000);
        c.drawRect(img.left, img.bottom - bh, img.right, img.bottom, p);
        text(c, o.status, img.left + pad, img.bottom - 8 * u, fs, white, Paint.Align.LEFT);
        float run = (now % 1400) / 1400f, seg = img.width() * .22f;
        float sx = img.left - seg + (img.width() + seg) * run;
        p.setColor(ORANGE);
        c.drawRect(Math.max(img.left, sx), img.bottom - 2 * u, Math.min(img.right, sx + seg), img.bottom, p);
        postInvalidateDelayed(40);
      }
    }

    private void tagRight(Canvas c, String s, float right, float y, float size, int color) {
      t.setTextSize(size);
      float tw = t.measureText(s);
      tag(c, s, right - tw - 6 * u, y, size, true, color);
    }

    private float modeBox(Canvas c, String mode, float x, float y, float size) {
      t.setTextSize(size);
      float tw = t.measureText(mode), bw = tw + 6 * u;
      p.setColor(0xf0f4f1e8);
      c.drawRoundRect(x, y - size * 1.02f, x + bw, y + size * .3f, 1.5f * u, 1.5f * u, p);
      t.setColor(0xff111111);
      t.clearShadowLayer();
      t.setTextAlign(Paint.Align.CENTER);
      c.drawText(mode, x + bw / 2, y, t);
      return bw;
    }

    private float iso(Canvas c, float right, float y, float size, String iso, boolean auto, int color) {
      if (iso.isEmpty()) return 0;
      float w = 0;
      if (auto) w += text(c, "A", right, y - size * .45f, size * .7f, color, Paint.Align.RIGHT) + 1.5f * u;
      w += text(c, iso, right - w, y, size * 1.1f, color, Paint.Align.RIGHT) + 3 * u;
      w += text(c, "ISO", right - w, y, size * .7f, color, Paint.Align.RIGHT);
      return w;
    }

    private float battery(Canvas c, float right, float y, float size, int level) {
      if (level < 0) return 0;
      float bw = size * 1.9f, bh = size * .9f, x = right - bw, top = y - bh;
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(1, .9f * u));
      p.setColor(0xf0f4f1e8);
      p.setShadowLayer(1.2f * u, 0, 0, 0x90000000);
      c.drawRect(x, top, x + bw - 2 * u, y, p);
      p.clearShadowLayer();
      p.setStyle(Paint.Style.FILL);
      c.drawRect(x + bw - 2 * u, top + bh * .3f, x + bw, y - bh * .3f, p);
      p.setColor(level <= 15 ? 0xffff5a3c : 0xf0f4f1e8);
      c.drawRect(x + 1.6f * u, top + 1.6f * u, x + 1.6f * u + (bw - 5.2f * u) * level / 100f, y - 1.6f * u, p);
      return bw;
    }

    private void scaleBar(Canvas c, float left, float right, float y, float size, double ev, boolean warn, long now) {
      float width = right - left;
      if (width < 40 * u) return;
      float mid = (left + right) / 2, span = Math.min(width, 150 * u) / 2, base = y - size * .3f;
      p.setStrokeWidth(Math.max(1, .9f * u));
      p.setColor(0xe8f4f1e8);
      p.setShadowLayer(1.2f * u, 0, 0, 0x90000000);
      for (int i = -9; i <= 9; i++) {
        float x = mid + span * i / 9f, len = i % 3 == 0 ? 5 * u : 2.6f * u;
        c.drawLine(x, base, x, base - len, p);
      }
      p.clearShadowLayer();
      for (int i = -3; i <= 3; i++)
        text(c, String.valueOf(Math.abs(i)), mid + span * i / 3f, base + size * .78f, size * .62f, 0xd8f4f1e8, Paint.Align.CENTER);
      double clamped = Math.max(-3, Math.min(3, ev));
      boolean blink = (warn || Math.abs(ev) > 3) && (now / 300) % 2 == 0;
      if (!blink) {
        float x = mid + (float) (span * clamped / 3);
        p.setColor(ORANGE);
        Path tri = new Path();
        tri.moveTo(x - 3.6f * u, base - 8.6f * u);
        tri.lineTo(x + 3.6f * u, base - 8.6f * u);
        tri.lineTo(x, base - 5.2f * u);
        tri.close();
        c.drawPath(tri, p);
      }
      if (warn || Math.abs(ev) > 3) postInvalidateDelayed(300);
    }

    private void histogram(Canvas c, float x, float y, float w, float h, int[] bins) {
      p.setStyle(Paint.Style.FILL);
      p.setColor(0x70000000);
      c.drawRect(x, y, x + w, y + h, p);
      int max = 1;
      for (int i = 1; i < bins.length - 1; i++) max = Math.max(max, bins[i]);
      path.reset();
      path.moveTo(x, y + h);
      for (int i = 0; i < bins.length; i++) {
        float px = x + w * i / (bins.length - 1f);
        float py = y + h - h * Math.min(1f, bins[i] / (float) max);
        path.lineTo(px, py);
      }
      path.lineTo(x + w, y + h);
      path.close();
      p.setColor(0xd8f4f1e8);
      c.drawPath(path, p);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(1, .8f * u));
      p.setColor(0x90f4f1e8);
      c.drawRect(x, y, x + w, y + h, p);
      p.setStyle(Paint.Style.FILL);
    }

    private void toast(Canvas c, String title, String sub, float cx, float cy, float size, float alpha) {
      t.setTextSize(size * 1.6f);
      t.setLetterSpacing(.08f);
      float tw = t.measureText(title);
      t.setLetterSpacing(0);
      t.setTextSize(size * .9f);
      float sw = sub == null ? 0 : t.measureText(sub);
      float bw = Math.max(tw, sw) + 28 * u, bh = (sub == null || sub.isEmpty() ? 30 : 44) * u;
      int a = (int) (alpha * 255);
      p.setColor(Color.argb((int) (alpha * 215), 12, 12, 11));
      c.drawRoundRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, 5 * u, 5 * u, p);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(1, u * .8f));
      p.setColor(Color.argb(a / 4, 255, 255, 255));
      c.drawRoundRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, 5 * u, 5 * u, p);
      p.setStyle(Paint.Style.FILL);
      t.setLetterSpacing(.08f);
      text(c, title, cx, cy + (sub == null || sub.isEmpty() ? size * .55f : -size * .1f), size * 1.6f, Color.argb(a, 244, 241, 232), Paint.Align.CENTER);
      t.setLetterSpacing(0);
      if (sub != null && !sub.isEmpty())
        text(c, sub, cx, cy + size * 1.6f, size * .9f, Color.argb(a, 214, 169, 140), Paint.Align.CENTER);
    }
  }
}
