import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.RawDeveloper;
import dev.grain.photo.develop.RawFrame;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Desktop harness for the RAW engine (no Android needed). Develops a Bayer RAW dump with a .cube LUT
 * and writes a binary PPM, so results can be compared against an independent reference (LibRaw).
 *
 * <p>Usage: java -cp out DevelopRaw raw.u16le meta.txt lut.cube out.ppm [gain] [lutOnlyTone]
 *
 * <p>meta.txt: key=value lines — width, height, cfa (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR), black (4
 * comma values, row-major 2x2), white, neutral (3), cm1 (9, XYZ->camera), fm1 (optional, 9),
 * illuminant1, iso, recipe (reggie|plain).
 */
public final class DevelopRaw {
  public static void main(String[] args) throws Exception {
    Map<String, String> meta = new HashMap<>();
    for (String line : Files.readAllLines(Paths.get(args[1]))) {
      int eq = line.indexOf('=');
      if (eq > 0) meta.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
    }
    int w = Integer.parseInt(meta.get("width")), h = Integer.parseInt(meta.get("height"));
    byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
    short[] data = new short[w * h];
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data);
    RawFrame f = new RawFrame(data, w, h, w);
    f.cfa = Integer.parseInt(meta.get("cfa"));
    double[] black = nums(meta.get("black"));
    for (int i = 0; i < 4; i++) f.black[i] = (float) black[i];
    f.white = Float.parseFloat(meta.get("white"));
    f.neutral = nums(meta.get("neutral"));
    f.colorMatrix1 = nums(meta.get("cm1"));
    if (meta.containsKey("fm1")) f.forwardMatrix1 = nums(meta.get("fm1"));
    f.illuminant1 = f.illuminant2 = Integer.parseInt(meta.getOrDefault("illuminant1", "21"));
    f.iso = Integer.parseInt(meta.getOrDefault("iso", "100"));
    CubeLut lut;
    if (args[2].endsWith(".glut"))
      try (DataInputStream in = new DataInputStream(new FileInputStream(args[2]))) {
        lut = CubeLut.read(in);
      }
    else
      try (FileReader in = new FileReader(args[2])) {
        lut = CubeLut.parse(in);
      }
    FujiRecipe recipe =
        "reggie".equals(meta.getOrDefault("recipe", "plain"))
            ? FujiRecipe.REGGIES_PORTRA
            : new FujiRecipe("plain", "Plain", "", "", "LUT", "LUT", FujiRecipe.DynamicRange.DR100,
                0, 0, 0, 0, -4, 0, 0, false, 0, 0, "As shot", 0, 0);
    RawDeveloper.Options o = new RawDeveloper.Options();
    if (args.length > 4) o.fixedGain = Double.parseDouble(args[4]);
    if (args.length > 5) o.stillsTone = !"lutOnlyTone".equals(args[5]);
    o.grain = false;
    int[] out = new int[w * h];
    long t = System.nanoTime();
    Map<String, Object> stats =
        RawDeveloper.develop(
            f, recipe, lut, o, (y, n, argb, width) -> System.arraycopy(argb, 0, out, y * width, n * width));
    System.out.println("ms=" + (System.nanoTime() - t) / 1000000);
    for (Map.Entry<String, Object> e : stats.entrySet()) {
      Object v = e.getValue();
      System.out.println(e.getKey() + "=" + (v instanceof double[] ? java.util.Arrays.toString((double[]) v)
          : v instanceof int[] ? java.util.Arrays.toString((int[]) v) : v));
    }
    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(args[3]), 1 << 20)) {
      os.write(("P6\n" + w + " " + h + "\n255\n").getBytes("US-ASCII"));
      for (int p : out) {
        os.write((p >> 16) & 255);
        os.write((p >> 8) & 255);
        os.write(p & 255);
      }
    }
  }

  static double[] nums(String s) {
    String[] t = s.split(",");
    double[] v = new double[t.length];
    for (int i = 0; i < t.length; i++) v[i] = Double.parseDouble(t[i].trim());
    return v;
  }
}
