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
