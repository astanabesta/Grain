package dev.grain.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The MERGE stops must not be confused with tripod HI-RES, and must not add a settle timer. */
public class DriveModesMergeTest {

  @Test
  public void mergeStopsAreFastRawOnlyModes() {
    assertTrue(DriveModes.isMerge(DriveModes.DRIVE_MERGE_DETAIL));
    assertTrue(DriveModes.isMerge(DriveModes.DRIVE_MERGE_PANO));
    assertFalse(DriveModes.isHiRes(DriveModes.DRIVE_MERGE_DETAIL));
    assertFalse(DriveModes.isHiRes(DriveModes.DRIVE_MERGE_PANO));
    assertEquals(10, DriveModes.DRIVES.length);
    assertEquals(4, DriveModes.mergeFramesFor(DriveModes.DRIVE_MERGE_DETAIL));
    assertEquals(4, DriveModes.mergeFramesFor(DriveModes.DRIVE_MERGE_PANO));
    assertEquals(0, DriveModes.driveTimerSeconds(DriveModes.DRIVE_MERGE_DETAIL));
    assertEquals(0, DriveModes.driveTimerSeconds(DriveModes.DRIVE_MERGE_PANO));
  }
}
