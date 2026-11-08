package dev.grain.photo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the crash that would otherwise vanish.
 *
 * <p>A crash in a camera app is usually a one-in-a-million race — a preview read-back landing on
 * the wrong frame, a surface torn down mid-read — and by the time the user has restarted the app
 * the system has already thrown the stack trace away. The user is left with "it crashed" and no
 * way to say why. This writes the trace to disk instead, so the next launch can show it and the
 * user can copy it into a message.
 *
 * <p>The handler deliberately keeps the platform's own handler as the fallback, so the system still
 * shows its "app keeps stopping" dialog and the process still dies the way Android expects.
 */
public final class CrashLog {
  private static final String FILE = "crash.log";
  private static final String PREF = "grain_crash";
  private static boolean installed;

  private CrashLog() {}

  /** Installs the crash handler. Safe to call more than once. */
  public static void install(Context context) {
    if (installed) return;
    installed = true;
    final Context app = context.getApplicationContext();
    final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            record(app, describe(thread, error));
          } catch (Throwable ignored) {
            // The crash handler must never become the crash.
          }
          if (previous != null) previous.uncaughtException(thread, error);
        });
  }

  /** The last crash this install recorded, or null. */
  public static String lastCrash(Context context) {
    try {
      return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("last", null);
    } catch (Throwable ignored) {
      return null;
    }
  }

  /** Forgets the recorded crash. */
  public static void clear(Context context) {
    try {
      context
          .getSharedPreferences(PREF, Context.MODE_PRIVATE)
          .edit()
          .remove("last")
          .remove("version")
          .apply();
    } catch (Throwable ignored) {
      // nothing to do
    }
  }

  /**
   * Shows the recorded crash, if there is one, and forgets it. Called on the first launch after
   * a crash so the user can copy the reason instead of guessing.
   */
  public static void showPending(final Activity activity) {
    final String text = lastCrash(activity);
    if (text == null || text.trim().isEmpty()) return;
    String crashVer = null;
    try {
      crashVer =
          activity.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("version", null);
    } catch (Throwable ignored) {
    }
    clear(activity);
    if (!BuildConfig.VERSION_NAME.equals(crashVer)) {
      // A crash from an earlier version that was just replaced by an update should not scare the
      // user on the first launch of the fixed build.
      return;
    }
    try {
      ScrollView scroll = new ScrollView(activity);
      TextView view = new TextView(activity);
      view.setText(text);
      view.setTypeface(Typeface.MONOSPACE);
      view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
      view.setGravity(Gravity.TOP | Gravity.START);
      int pad = dp(activity, 14);
      view.setPadding(pad, pad, pad, pad);
      view.setTextIsSelectable(true);
      scroll.addView(view);
      new AlertDialog.Builder(activity)
          .setTitle("Grain crashed last time")
          .setMessage("Copy this report and send it, and Grain can fix the real cause.")
          .setView(scroll)
          .setPositiveButton("Close", null)
          .setNeutralButton(
              "Copy",
              (d, w) -> {
                try {
                  ClipboardManager clip =
                      (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                  if (clip != null) clip.setPrimaryClip(ClipData.newPlainText("Grain crash", text));
                  Toast.makeText(activity, "Crash report copied", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                  // the clipboard is not worth crashing over
                }
              })
          .show();
    } catch (Throwable ignored) {
      // a crash report that cannot be shown is not worth a second crash
    }
  }

  private static void record(Context context, String text) {
    try {
      File file = new File(context.getFilesDir(), FILE);
      try (FileOutputStream out = new FileOutputStream(file, false)) {
        out.write(text.getBytes("UTF-8"));
      }
    } catch (Throwable ignored) {
      // best effort
    }
    try {
      context
          .getSharedPreferences(PREF, Context.MODE_PRIVATE)
          .edit()
          .putString("last", text)
          .putString("version", BuildConfig.VERSION_NAME)
          .apply();
    } catch (Throwable ignored) {
      // best effort
    }
    // Also drop a copy in the public Downloads folder. If the app dies on every launch the user
    // can never open the diagnostics screen to read the report - but the file is right there on
    // the phone, where they can pick it up and send it.
    try {
      String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
      new PhotoStore(context.getApplicationContext())
          .writeText("Grain-crash-" + stamp + ".txt", text);
    } catch (Throwable ignored) {
      // best effort
    }
  }

  /** Saves a caught startup failure to crash.log and Download/Grain without marking next launch. */
  public static void recordCaught(Context context, Throwable error) {
    try {
      Context app = context.getApplicationContext();
      String text = describe(error);
      try {
        File file = new File(app.getFilesDir(), FILE);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
          out.write(text.getBytes("UTF-8"));
        }
      } catch (Throwable ignored) {
      }
      try {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        new PhotoStore(app).writeText("Grain-crash-" + stamp + ".txt", text);
      } catch (Throwable ignored) {
      }
    } catch (Throwable ignored) {
    }
  }

  /** A full crash report for a throwable that was caught rather than fatal. */
  public static String describe(Throwable error) {
    return describe(Thread.currentThread(), error);
  }

  private static String describe(Thread thread, Throwable error) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    pw.println("Grain crash report");
    pw.println("Version: " + BuildConfig.VERSION_NAME);
    pw.println("When   : " + new Date());
    pw.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    pw.println("Device : " + Build.MANUFACTURER + " " + Build.MODEL);
    pw.println("Thread : " + (thread == null ? "?" : thread.getName()));
    pw.println();
    if (error != null) error.printStackTrace(pw);
    pw.flush();
    return sw.toString();
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
