package dev.grain.photo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** A shader/driver failure on GLThread must degrade to the old viewfinder, never kill the app. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GlFailureFallbackTest {

  @Test
  public void glThreadFailureSwapsPreviewToTextureView() {
    Context app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences("grain", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("onboardingDone", true)
        .putBoolean("notifyUpdates", false)
        .commit();

    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity activity = controller.get();
    assertFalse("startup must have succeeded", activity.startupFailed);

    // Robolectric has no GL, so pretend the GPU viewfinder started and then failed on GLThread.
    activity.useGl = true;
    activity.handleGlFailure(
        new RuntimeException("shader: 0:5: S0032: no default precision defined for variable 'uLut'"));

    assertFalse(activity.useGl);
    assertNotNull(activity.fallbackTexture);
    assertTrue("fallback view must be attached to the preview frame", activity.fallbackTexture.getParent() != null);
    assertNotNull(activity.preview); // GrainCamera keeps the same delegate object throughout

    controller.pause().stop().destroy();
  }
}
