package dev.grain.photo;

import static org.junit.Assert.assertTrue;

import dev.grain.photo.develop.PortraitBokeh;
import org.junit.Test;

/** Sigma field from relative depth: far saturates, focus plane and same-depth items stay sharp. */
public class PortraitDepthDebugTest {
  @Test
  public void depthRadiusFieldSaturatesFarAndKeepsPlane() {
    int w = 192, h = 144;
    int qw = (w + 3) / 4, qh = (h + 3) / 4;
    float[] mask = new float[qw * qh];
    float[] depth = new float[qw * qh];
    for (int y = 0; y < qh; y++)
      for (int x = 0; x < qw; x++) {
        double dx = (x * 4 + 2 - 70) / 26.0, dy = (y * 4 + 2 - 66) / 40.0;
        boolean person = dx * dx + dy * dy <= 1.0;
        mask[y * qw + x] = person ? 1f : 0f;
        depth[y * qw + x] = person ? 0.85f : 0.25f;
      }
    for (int y = 24; y < 35; y++)
      for (int x = 29; x < 44; x++) depth[y * qw + x] = 0.83f; // bed at subject depth

    float rMaxQ = 4.6f;
    float[] rf = new float[qw * qh];
    boolean ok = PortraitBokeh.depthRadii(mask, depth, rf, qw, qh, rMaxQ);
    System.out.println("DBG-DEPTH ok=" + ok + " bed=" + rf[30 * qw + 34]
        + " farBg=" + rf[2 * qw + 45] + " person=" + rf[17 * qw + 17]);
    assertTrue("field produced", ok);
    assertTrue("person core sharp", rf[17 * qw + 17] == 0f);
    assertTrue("same-depth object sharp (" + rf[30 * qw + 34] + ")", rf[30 * qw + 34] < 0.25f);
    assertTrue("far background saturates (" + rf[2 * qw + 45] + ")", rf[2 * qw + 45] >= rMaxQ * 0.75f);
  }
}
