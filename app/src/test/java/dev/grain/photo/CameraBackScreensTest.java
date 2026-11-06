package dev.grain.photo;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLog;

/**
 * Renders the camera back in its main states so the layout can be checked without a phone, and
 * drives MainActivity through the same key calls the buttons make, so a crash in the camera-back
 * code fails the build. Runs only when GRAIN_SCREENS=1 (dev branches in CI; see app/build.gradle).
 * The pictures go to app/build/screens. Screen: Redmi Note 13 Pro+ (1220 × 2712, 440 dpi).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w443dp-h986dp-port-440dpi")
public class CameraBackScreensTest {
  private static final int W = 1220, H = 2712;
  private final File dir = new File("build/screens");
  private final List<String> failures = new ArrayList<>();
  private MainActivity activity;

  private interface Action {
    void run() throws Exception;
  }

  @Test
  public void screens() throws Exception {
    dir.mkdirs();
    ShadowLog.clear();
    activity = Robolectric.buildActivity(MainActivity.class).setup().get();
    step("01-portrait", () -> {});
    step("02-portrait-portra", () -> selectFilm("PTR"));
    step("03-q-menu", () -> key(CameraBack.K_Q, 0));
    step(
        "04-q-wb-changed",
        () -> {
          key(CameraBack.K_LEVER, CameraBack.LEVER_RIGHT);
          key(CameraBack.K_COMMAND, 1);
        });
    step(
        "05-menu",
        () -> {
          key(CameraBack.K_Q, 0);
          key(CameraBack.K_OK, 0);
        });
    step("06-menu-setup", () -> key(CameraBack.K_LEVER, CameraBack.LEVER_LEFT));
    step(
        "07-evf",
        () -> {
          key(CameraBack.K_DISP, 0);
          key(CameraBack.K_EYE, 0);
        });
    step(
        "08-landscape",
        () -> {
          key(CameraBack.K_EVF_BACK, 0);
          rotate(90);
        });
    step("09-landscape-evf", () -> key(CameraBack.K_EYE, 0));
    step(
        "10-landscape-q",
        () -> {
          key(CameraBack.K_EVF_BACK, 0);
          key(CameraBack.K_Q, 0);
        });
    step(
        "11-manual-timer-ael",
        () -> {
          key(CameraBack.K_Q, 0);
          rotate(0);
          key(CameraBack.K_SS, 5);
          key(CameraBack.K_ISO_TOGGLE, 0);
          key(CameraBack.K_SS, 8);
          key(CameraBack.K_DRIVE, 0);
          key(CameraBack.K_AEL, 0);
          key(CameraBack.K_EV, 8);
        });
    step("12-info-off", () -> key(CameraBack.K_DISP, 0));
    step(
        "13-silver-1-1",
        () -> {
          key(CameraBack.K_DISP, 0);
          back().setFinish(1);
          Method ratio = MainActivity.class.getDeclaredMethod("cycleRatio", int.class);
          ratio.setAccessible(true);
          ratio.invoke(activity, 1);
        });
    step("14-landscape-silver", () -> rotate(270));
    step(
        "15-power-off",
        () -> {
          rotate(0);
          key(CameraBack.K_POWER, 0);
        });
    step("16-power-on", () -> key(CameraBack.K_POWER, 1));
    StringBuilder logs = new StringBuilder();
    for (ShadowLog.LogItem item : ShadowLog.getLogs())
      if (item.type >= Log.WARN || "Grain".equals(item.tag))
        logs.append(item.type).append(' ').append(item.tag).append(": ").append(item.msg)
            .append(item.throwable == null ? "" : "\n" + trace(item.throwable)).append('\n');
    write("logs.txt", logs.toString());
    write("errors.txt", failures.isEmpty() ? "none\n" : String.join("\n\n", failures));
    assertTrue(String.join("\n\n", failures), failures.isEmpty());
  }

  private void key(int key, int value) {
    activity.key(key, value);
  }

  private CameraBack back() throws Exception {
    Field f = MainActivity.class.getDeclaredField("back");
    f.setAccessible(true);
    return (CameraBack) f.get(activity);
  }

  private void selectFilm(String code) throws Exception {
    Field films = MainActivity.class.getDeclaredField("dialFilms");
    films.setAccessible(true);
    Recipe[] list = (Recipe[]) films.get(activity);
    Method filmCode = MainActivity.class.getDeclaredMethod("filmCode", Recipe.class);
    filmCode.setAccessible(true);
    for (int i = 0; i < list.length; i++)
      if (code.equals(filmCode.invoke(null, list[i]))) {
        key(CameraBack.K_FILM, i);
        return;
      }
    throw new AssertionError("No film " + code + " on the dial");
  }

  private void rotate(int degrees) throws Exception {
    Method m = MainActivity.class.getDeclaredMethod("applyUiRotation", int.class, boolean.class);
    m.setAccessible(true);
    m.invoke(activity, degrees, false);
  }

  private void settle() {
    View decor = activity.getWindow().getDecorView();
    for (int i = 0; i < 4; i++) {
      shadowOf(Looper.getMainLooper()).idle();
      decor.measure(
          View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
      decor.layout(0, 0, W, H);
    }
  }

  private void step(String name, Action action) {
    try {
      action.run();
      settle();
      Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
      activity.getWindow().getDecorView().draw(new Canvas(b));
      Bitmap small = Bitmap.createScaledBitmap(b, W / 2, H / 2, true);
      try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
        small.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
    } catch (Throwable t) {
      failures.add(name + ":\n" + trace(t));
    }
  }

  private static String trace(Throwable t) {
    StringWriter s = new StringWriter();
    t.printStackTrace(new PrintWriter(s));
    return s.toString();
  }

  private void write(String name, String text) throws Exception {
    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }
}
