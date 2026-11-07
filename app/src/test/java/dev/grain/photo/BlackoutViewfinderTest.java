package dev.grain.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BlackoutViewfinderTest {

  @Test
  public void optionBBlackoutViewfinderHidesShutterAndPlacesPreviewInTopLeftEyepiece() {
    Context ctx = RuntimeEnvironment.getApplication();
    AtomicInteger lastKey = new AtomicInteger(-1);
    AtomicBoolean tappedPreview = new AtomicBoolean(false);

    CameraBack back =
        new CameraBack(
            ctx,
            new CameraBack.Host() {
              @Override
              public void key(int key, int value) {
                lastKey.set(key);
              }

              @Override
              public void lcdTap(float rawX, float rawY, float x, float y) {
                tappedPreview.set(true);
              }

              @Override
              public void lcdHold(float x, float y, boolean held) {}

              @Override
              public void pinch(float factor) {}

              @Override
              public void previewRect(RectF rect) {}
            });

    back.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(860, View.MeasureSpec.EXACTLY));
    back.layout(0, 0, 400, 860);
    assertEquals(View.VISIBLE, back.shutter.getVisibility());
    assertEquals(View.VISIBLE, back.eyepiece.getVisibility());

    // Clicking the top-left viewfinder eyepiece triggers K_EYE
    back.eyepiece.performClick();
    assertEquals(CameraBack.K_EYE, lastKey.get());

    // Enter Blackout Viewfinder (Option B + Shutter Ring Hidden)
    back.setEvf(true);
    back.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(860, View.MeasureSpec.EXACTLY));
    back.layout(0, 0, 400, 860);

    assertEquals(View.GONE, back.shutter.getVisibility());
    assertEquals(View.GONE, back.evfBack.getVisibility());
    assertEquals(View.GONE, back.q.getVisibility());
    assertEquals(View.GONE, back.eyepiece.getVisibility());

    RectF vf = back.screenRect();
    assertEquals(16f, vf.left, 0.5f);
    assertEquals(12f, vf.top, 0.5f);
    assertEquals(152f, vf.right, 0.5f);
    assertEquals(104f, vf.bottom, 0.5f);

    // Tapping inside the live top-left viewfinder preview focuses without exiting blackout
    lastKey.set(-1);
    dispatchTap(back.osd, 60f, 50f);
    assertTrue(tappedPreview.get());
    assertEquals(-1, lastKey.get());

    // Tapping in the blacked-out area exits back to the camera body (K_EVF_BACK)
    tappedPreview.set(false);
    dispatchTap(back.osd, 240f, 500f);
    assertFalse(tappedPreview.get());
    assertEquals(CameraBack.K_EVF_BACK, lastKey.get());

    // Landscape Blackout Viewfinder keeps preview in the top-left eyepiece corner
    back.setLandscape(true);
    back.measure(
        View.MeasureSpec.makeMeasureSpec(860, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
    back.layout(0, 0, 860, 400);
    RectF landVf = back.screenRect();
    assertEquals(32f, landVf.left, 0.5f);
    assertEquals(8f, landVf.top, 0.5f);
    assertEquals(184f, landVf.right, 0.5f);
    assertEquals(110f, landVf.bottom, 0.5f);
    assertEquals(View.GONE, back.shutter.getVisibility());
  }

  @Test
  public void commandSliderEmitsContinuousZoomDeltasInPlaybackAndDiscreteStepsOtherwise() {
    Context ctx = RuntimeEnvironment.getApplication();
    AtomicBoolean continuousMode = new AtomicBoolean(false);
    AtomicInteger discreteSteps = new AtomicInteger(0);
    final float[] slideSum = {0f};

    CameraBack back =
        new CameraBack(
            ctx,
            new CameraBack.Host() {
              @Override
              public void key(int key, int value) {
                if (key == CameraBack.K_COMMAND) discreteSteps.addAndGet(value);
              }

              @Override
              public boolean commandContinuousZoom() {
                return continuousMode.get();
              }

              @Override
              public void commandSlide(float deltaUnits) {
                slideSum[0] += deltaUnits;
              }

              @Override
              public void lcdTap(float rawX, float rawY, float x, float y) {}

              @Override
              public void lcdHold(float x, float y, boolean held) {}

              @Override
              public void pinch(float factor) {}

              @Override
              public void previewRect(RectF rect) {}
            });

    back.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(860, View.MeasureSpec.EXACTLY));
    back.layout(0, 0, 400, 860);

    // 1. In camera/menu mode (continuousMode == false), dragging emits discrete K_COMMAND steps
    long now = SystemClock.uptimeMillis();
    MotionEvent d1 = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 12f, 0);
    MotionEvent m1 = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, 68f, 12f, 0);
    MotionEvent u1 = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, 68f, 12f, 0);
    back.command.dispatchTouchEvent(d1);
    back.command.dispatchTouchEvent(m1);
    back.command.dispatchTouchEvent(u1);
    d1.recycle();
    m1.recycle();
    u1.recycle();
    assertTrue(discreteSteps.get() > 0);
    assertEquals(0f, slideSum[0], 1e-4f);

    // 2. In playback mode (continuousMode == true), dragging emits smooth commandSlide deltas
    continuousMode.set(true);
    discreteSteps.set(0);
    MotionEvent d2 = MotionEvent.obtain(now + 50, now + 50, MotionEvent.ACTION_DOWN, 20f, 12f, 0);
    MotionEvent m2 = MotionEvent.obtain(now + 50, now + 66, MotionEvent.ACTION_MOVE, 55f, 12f, 0);
    MotionEvent m3 = MotionEvent.obtain(now + 50, now + 82, MotionEvent.ACTION_MOVE, 35f, 12f, 0);
    MotionEvent u2 = MotionEvent.obtain(now + 50, now + 98, MotionEvent.ACTION_UP, 35f, 12f, 0);
    back.command.dispatchTouchEvent(d2);
    back.command.dispatchTouchEvent(m2);
    assertTrue(slideSum[0] > 10f);
    float zoomedInDelta = slideSum[0];
    back.command.dispatchTouchEvent(m3);
    assertTrue(slideSum[0] < zoomedInDelta);
    back.command.dispatchTouchEvent(u2);
    d2.recycle();
    m2.recycle();
    m3.recycle();
    u2.recycle();
    assertEquals(0, discreteSteps.get());
  }

  private static void dispatchTap(View target, float x, float y) {
    long now = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
    MotionEvent up = MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_UP, x, y, 0);
    target.dispatchTouchEvent(down);
    target.dispatchTouchEvent(up);
    down.recycle();
    up.recycle();
  }
}
