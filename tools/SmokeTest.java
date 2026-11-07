import dev.grain.photo.*;

public final class SmokeTest {
  public static void main(String[] args) {
    int count = 0;
    for (String ratio : Framing.RATIOS)
      for (double z : new double[] {1, 1.5, 2, 3, 5}) {
        int[] c = Framing.crop(3060, 4080, ratio, z);
        if (c[0] < 0 || c[1] < 0 || c[0] + c[2] > 3060 || c[1] + c[3] > 4080)
          throw new AssertionError("Invalid crop");
        if (Math.abs((double) c[2] / c[3] - Framing.portraitRatio(ratio)) > .002)
          throw new AssertionError("Wrong ratio");
        count++;
      }
    for (int r = 0; r < 4; r++)
      for (int ch = 0; ch < 3; ch++)
        for (int strength = 0; strength <= 100; strength++) {
          float[] a = CurveMath.channel(r, ch, strength, 32);
          for (int i = 0; i < 32; i++) {
            if (!Float.isFinite(a[i * 2 + 1]) || a[i * 2 + 1] < 0 || a[i * 2 + 1] > 1)
              throw new AssertionError("Illegal curve");
            if (i > 0 && a[i * 2 + 1] < a[i * 2 - 1])
              throw new AssertionError("Nonmonotonic curve");
          }
          count++;
        }
    if (Framing.zoom(Double.NaN) != 1) throw new AssertionError("NaN zoom");
    for(int sensor:new int[]{90,270}){
      float[] corners=PreviewMath.destinationCorners(300,400,1440,1080,sensor,0,1);
      float[] expected={0,0,300,0,300,400,0,400};
      for(int i=0;i<8;i++)if(Math.abs(corners[i]-expected[i])>.01)throw new AssertionError("Double preview rotation");
      count++;
    }
    float[] toe = CurveMath.channel(3, 0, 80, 32);
    float sampled = 0;
    for (int i = 2; i < toe.length; i += 2) {
      if (.01f <= toe[i]) {
        float t = (.01f - toe[i - 2]) / (toe[i] - toe[i - 2]);
        sampled = toe[i - 1] + t * (toe[i + 1] - toe[i - 1]);
        break;
      }
    }
    if (Math.abs(sampled - .086242) > .001) throw new AssertionError("Shadow toe undersampled");
    count++;
    for (int cnt : FocusMath.BKT_COUNTS) {
      for (int step = 0; step < 3; step++) {
        for (float start : new float[] {0f, .1f, .5f, .9f, 1f}) {
          float[] plan = FocusMath.bracketFractions(start, cnt, step);
          if (plan.length != cnt) throw new AssertionError("Wrong bracket count");
          for (int i = 0; i < plan.length; i++) {
            if (plan[i] < 0f || plan[i] > 1f) throw new AssertionError("Bracket out of bounds");
            if (i > 0 && plan[i] <= plan[i - 1]) throw new AssertionError("Bracket non-increasing");
          }
          count++;
        }
      }
    }
    if (!"P".equals(ExposureMath.modeLabel(0, 0))
        || !"S".equals(ExposureMath.modeLabel(8_000_000L, 0))
        || !"ISO".equals(ExposureMath.modeLabel(0, 400))
        || !"M".equals(ExposureMath.modeLabel(8_000_000L, 400)))
      throw new AssertionError("Wrong exposure mode label");
    ExposureMath.Plan sp =
        ExposureMath.planStill(
            2_000_000L, 0, 0.0, 0.0, 16_000_000.0 * 100.0, 10_000L, 1_000_000_000L, 50, 6400);
    if (sp == null || sp.shutterNs != 2_000_000L || sp.iso != 800)
      throw new AssertionError("Shutter priority math broken");
    long[] pv = ExposureMath.planPreview(1_000_000_000L, 100, 10_000L, 1_000_000_000L, 50, 6400);
    if (pv[0] != ExposureMath.MAX_PREVIEW_EXPOSURE_NS || pv[1] != 800)
      throw new AssertionError("Slow shutter preview ISO boost broken");
    count += 3;
    System.out.println("PASS: " + count + " crop/curve/preview/focus/exposure cases; no Android SDK used.");
  }
}
