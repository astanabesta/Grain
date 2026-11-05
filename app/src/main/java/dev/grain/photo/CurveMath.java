package dev.grain.photo;

/** Experimental, device-uncalibrated one-dimensional RGB responses, not 3D film emulations. */
public final class CurveMath {
  private CurveMath() {}

  public static float[] channel(int recipe, int channel, int strength, int points) {
    points = Math.max(2, points);
    float[] curve = new float[points * 2];
    double amount = Math.max(0, Math.min(100, strength)) / 100.0;
    for (int i = 0; i < points; i++) {
      // Camera2 interpolates in linear-input space. Uniform linear knots put the first
      // nonzero knot at 0.032 with 32 points, skipping most of the sRGB shadow toe.
      // Space knots by encoded brightness instead; x remains a linear-light input.
      double base = (double) i / (points - 1);
      double x = base <= .04045 ? base / 12.92 : Math.pow((base + .055) / 1.055, 2.4);
      double lift = Math.pow(1 - base, 3) * base, shoulder = base * base * (1 - base);
      double y = base;
      switch (recipe) {
        case 0:
          y +=
              .10 * lift
                  - .10 * shoulder
                  + (channel == 0 ? .045 : channel == 2 ? -.045 : 0) * Math.sin(Math.PI * base);
          break;
        case 1:
          y +=
              .14 * lift
                  - .13 * shoulder
                  + (channel == 2 ? .035 : channel == 0 ? -.035 : .012) * Math.sin(Math.PI * base);
          break;
        case 2:
          y +=
              .13 * lift
                  - .15 * shoulder
                  + (channel == 0 ? .07 : channel == 2 ? -.07 : 0) * Math.sin(Math.PI * base);
          break;
        case 3:
          y +=
              -.14 * lift
                  + .10 * shoulder
                  + (channel == 2 ? .025 : channel == 0 ? -.025 : 0) * Math.sin(Math.PI * base);
          break;
        default:
          break;
      }
      curve[i * 2] = (float) x;
      curve[i * 2 + 1] = (float) Math.max(0, Math.min(1, base + (y - base) * amount));
      if (i > 0) curve[i * 2 + 1] = Math.max(curve[i * 2 - 1], curve[i * 2 + 1]);
    }
    return curve;
  }
}
