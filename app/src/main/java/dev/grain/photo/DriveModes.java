package dev.grain.photo;

import dev.grain.photo.develop.HiResMerger;

/**
 * Drive-dial positions and tripod super-resolution parameters. Kept free of Android framework
 * classes so every stop on the dial can be exercised in fast pure-JVM unit tests.
 */
public final class DriveModes {
  public static final int DRIVE_SINGLE = 0,
      DRIVE_TIMER_3 = 1,
      DRIVE_TIMER_5 = 2,
      DRIVE_FS_BKT = 3,
      DRIVE_FOCUS_BKT = 4,
      DRIVE_HIRES_36 = 5,
      DRIVE_HIRES_48 = 6,
      DRIVE_HIRES_112 = 7;

  public static final String[] DRIVES = {
    "SINGLE",
    "TIMER 3S",
    "TIMER 5S",
    "FS BKT",
    "FOCUS BKT",
    "HI-RES 1 36MP",
    "HI-RES 2 48MP",
    "HI-RES 3 112MP"
  };

  private DriveModes() {}

  /** True for the tripod super-resolution drive modes. */
  public static boolean isHiRes(int drive) {
    return drive == DRIVE_HIRES_36 || drive == DRIVE_HIRES_48 || drive == DRIVE_HIRES_112;
  }

  /** The self-timer length a drive mode implies (4 s for 4-frame Hi-Res, 6 s for 9-frame). */
  public static int driveTimerSeconds(int drive) {
    if (drive == DRIVE_TIMER_3) return 3;
    if (drive == DRIVE_TIMER_5) return 5;
    if (drive == DRIVE_HIRES_112) return 6;
    if (isHiRes(drive)) return 4;
    return 0;
  }

  /** The nominal output size of a tripod super-resolution drive mode, in megapixels. */
  public static int hiResMegapixels(int drive) {
    if (drive == DRIVE_HIRES_112) return 112;
    return drive == DRIVE_HIRES_48 ? 48 : 36;
  }

  /** How many RAW frames a tripod super-resolution drive mode merges. */
  public static int hiResFramesFor(int drive) {
    return drive == DRIVE_HIRES_112 ? 9 : 4;
  }

  /** The target pixel count a tripod super-resolution drive mode merges toward. */
  public static long hiResTargetPixelsFor(int drive) {
    if (drive == DRIVE_HIRES_112) return HiResMerger.TARGET_PIXELS_112;
    return drive == DRIVE_HIRES_48 ? HiResMerger.TARGET_PIXELS_48 : HiResMerger.TARGET_PIXELS;
  }

  /** The linear gain a drive mode's frame count can resolve: sqrt(frames). */
  public static double hiResMaxScale(int drive) {
    return drive == DRIVE_HIRES_112 ? HiResMerger.MAX_SCALE_9 : 2.0;
  }

  /** Short label for a tripod super-resolution drive mode. */
  public static String hiResLabel(int drive) {
    if (drive == DRIVE_HIRES_112) return "HI-RES 3 112MP";
    return drive == DRIVE_HIRES_48 ? "HI-RES 2 48MP" : "HI-RES 1 36MP";
  }

  /**
   * The vibration amplitude for one step. Four frames use the three amplitudes that were measured
   * to work. Nine frames walk a wider ladder so the nine frames spread across sub-pixel phases.
   */
  public static int oisAmplitude(int step, int frames) {
    if (frames <= 4) return step == 1 ? 105 : step == 2 ? 145 : 125;
    int[] ladder = {0, 55, 85, 115, 145, 180, 210, 240, 255};
    return ladder[Math.max(1, Math.min(ladder.length - 1, step))];
  }
}
