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
    System.out.println("PASS: " + count + " crop/curve cases; no Android SDK used.");
  }
}
