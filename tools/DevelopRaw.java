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
 * illuminant1, iso, recipe (reggie|plain|a published recipe key|sim-LUTNAME); optional cm2/fm2/illuminant2, shading (path to rows x cols x 4
 * little-endian float32, Camera2 order) with shadingRows/shadingCols, and noise (8 values: S,O per CFA
 * position, row-major). System properties: -Dgrain=true, -Dseed=N, -Dthreads=N, -Dev=EV, -Dnr=scale,
 * -Dsharpen=scale, -Dhalation=0..10, -Dbloom=0..10, -Dclarity=-5..5, -Drecipe=key, -Dwb=DAYLIGHT|KELVIN|...,
 * -Dkelvin=K, -Ddr=DR400|DRP_STRONG|..., -Dfilter=0..3, -DshiftR/-DshiftB=-9..9, -Dhl/-Dsh=tone,
 * -Ddevelopment=CLEAN|FIRST_VERSION, -Dsharpness=-4..4 and -DhighIsoNr=-4..4 (recipe settings),
 * -Ddebug=file (pre-NR luma codes then modelled noise variance, float32 planes).
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
    if (meta.containsKey("cm2")) {
      f.colorMatrix2 = nums(meta.get("cm2"));
      if (meta.containsKey("fm2")) f.forwardMatrix2 = nums(meta.get("fm2"));
      f.illuminant2 = Integer.parseInt(meta.getOrDefault("illuminant2", "17"));
    }
    f.iso = Integer.parseInt(meta.getOrDefault("iso", "100"));
    if (meta.containsKey("shading")) {
      f.shadingRows = Integer.parseInt(meta.get("shadingRows"));
      f.shadingColumns = Integer.parseInt(meta.get("shadingCols"));
      byte[] sb = Files.readAllBytes(Paths.get(meta.get("shading")));
      f.shading = new float[f.shadingRows * f.shadingColumns * 4];
      ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f.shading);
      f.shadingLeft = 0;
      f.shadingTop = 0;
      f.shadingWidth = w;
      f.shadingHeight = h;
    }
    if (meta.containsKey("noise")) {
      double[] nz = nums(meta.get("noise"));
      f.noiseS = new double[4];
      f.noiseO = new double[4];
      for (int k = 0; k < 4; k++) {
        f.noiseS[k] = nz[2 * k];
        f.noiseO[k] = nz[2 * k + 1];
      }
    }
    CubeLut lut;
    if (args[2].endsWith(".glut"))
      try (DataInputStream in = new DataInputStream(new FileInputStream(args[2]))) {
        lut = CubeLut.read(in);
      }
    else
      try (FileReader in = new FileReader(args[2])) {
        lut = CubeLut.parse(in);
      }
    String wanted = System.getProperty("recipe", meta.getOrDefault("recipe", "plain"));
    FujiRecipe recipe =
        new FujiRecipe("plain", "Plain", "", "", "LUT", "LUT", FujiRecipe.DynamicRange.DR100,
            0, 0, 0, 0, -4, 0, 0, false, 0, 0, "As shot", 0, 0);
    if ("reggie".equals(wanted)) recipe = FujiRecipe.REGGIES_PORTRA;
    for (FujiRecipe p : FujiRecipe.PUBLISHED) if (p.key.equals(wanted)) recipe = p;
    if (wanted.startsWith("sim-")) recipe = FujiRecipe.standard(wanted.substring(4).toUpperCase(java.util.Locale.ROOT), wanted);
    String[] tuned = {"halation", "bloom", "clarity", "wb", "kelvin", "dr", "filter", "shiftR", "shiftB", "hl", "sh",
        "development", "sharpness", "highIsoNr"};
    boolean any = false;
    for (String k : tuned) any |= System.getProperty(k) != null;
    if (any) {
      FujiRecipe.Tuning t = recipe.tuning();
      t.halation = Integer.getInteger("halation", t.halation);
      t.bloom = Integer.getInteger("bloom", t.bloom);
      t.clarity = Integer.getInteger("clarity", t.clarity);
      if (System.getProperty("wb") != null) t.wbMode = FujiRecipe.WhiteBalance.valueOf(System.getProperty("wb"));
      t.kelvin = Integer.getInteger("kelvin", t.kelvin);
      if (System.getProperty("dr") != null) t.dynamicRange = FujiRecipe.DynamicRange.valueOf(System.getProperty("dr"));
      t.monoFilter = Integer.getInteger("filter", t.monoFilter);
      t.wbShiftRed = Integer.getInteger("shiftR", t.wbShiftRed);
      t.wbShiftBlue = Integer.getInteger("shiftB", t.wbShiftBlue);
      if (System.getProperty("hl") != null) t.highlight = Double.parseDouble(System.getProperty("hl"));
      if (System.getProperty("sh") != null) t.shadow = Double.parseDouble(System.getProperty("sh"));
      if (System.getProperty("development") != null)
        t.development = FujiRecipe.Development.valueOf(System.getProperty("development"));
      t.sharpness = Integer.getInteger("sharpness", t.sharpness);
      t.noiseReduction = Integer.getInteger("highIsoNr", t.noiseReduction);
      recipe = recipe.with(t);
    }
    System.out.println("recipe=" + recipe.key + " " + java.util.Arrays.deepToString(recipe.settings()));
    RawDeveloper.Options o = new RawDeveloper.Options();
    if (args.length > 4) o.fixedGain = Double.parseDouble(args[4]);
    if (args.length > 5) o.stillsTone = !"lutOnlyTone".equals(args[5]);
    o.grain = Boolean.getBoolean("grain");
    o.grainSeed = Long.getLong("seed", 1L);
    o.threads = Integer.getInteger("threads", o.threads);
    if (System.getProperty("ev") != null) o.exposureEv = Double.parseDouble(System.getProperty("ev"));
    if (System.getProperty("nr") != null) o.lumaNr = Double.parseDouble(System.getProperty("nr"));
    if (System.getProperty("sharpen") != null) o.sharpen = Double.parseDouble(System.getProperty("sharpen"));
    String debug = System.getProperty("debug");
    if (debug != null) {
      o.debugLuma = new float[w * h];
      o.debugNoise = new float[w * h];
      o.debugQuarter = new float[((w + 3) / 4) * ((h + 3) / 4) * 4];
    }
    int[] out = new int[w * h];
    int repeat = Integer.getInteger("repeat", 1);
    for (int i = 1; i < repeat; i++)
      RawDeveloper.develop(f, recipe, lut, o, (y, n, argb, width) -> {});
    long t = System.nanoTime();
    Map<String, Object> stats =
        RawDeveloper.develop(
            f, recipe, lut, o, (y, n, argb, width) -> System.arraycopy(argb, 0, out, y * width, n * width));
    System.out.println("ms=" + (System.nanoTime() - t) / 1000000);
    for (Map.Entry<String, Object> e : stats.entrySet()) {
      Object v = e.getValue();
      System.out.println(e.getKey() + "=" + (v instanceof double[] ? java.util.Arrays.toString((double[]) v)
          : v instanceof int[] ? java.util.Arrays.toString((int[]) v)
          : v instanceof long[] ? java.util.Arrays.toString((long[]) v)
          : v instanceof double[] ? java.util.Arrays.toString((double[]) v) : v));
    }
    if (debug != null) {
      ByteBuffer bb = ByteBuffer.allocate(w * h * 8).order(ByteOrder.LITTLE_ENDIAN);
      for (float v : o.debugLuma) bb.putFloat(v);
      for (float v : o.debugNoise) bb.putFloat(v);
      Files.write(Paths.get(debug), bb.array());
      ByteBuffer qb = ByteBuffer.allocate(o.debugQuarter.length * 4).order(ByteOrder.LITTLE_ENDIAN);
      for (float v : o.debugQuarter) qb.putFloat(v);
      Files.write(Paths.get(debug + ".q"), qb.array());
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
