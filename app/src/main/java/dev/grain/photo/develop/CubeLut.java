package dev.grain.photo.develop;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A 3-D colour lookup table in the Adobe/Resolve .cube layout (red index changes fastest), sampled
 * with trilinear interpolation. Grain only ever loads official LUTs (Fujifilm's F-Log2 film
 * simulations, Sony's s709) that the user downloaded and imported on their own phone; no LUT data
 * ships with the app.
 *
 * <p>The engine always samples with F-Log2 / F-Gamut codes. A table made for another input ({@link
 * Input}) converts each sample exactly before the lookup: F-Log2 code to scene-linear BT.2020, a 3x3
 * matrix to the table's gamut, and the table's own log encoding.
 */
public final class CubeLut {
  /** The encoding a table's input expects. Never reorder (stored by name). */
  public enum Input {
    FLOG2_FGAMUT("F-Log2 / F-Gamut"),
    SLOG3_SGAMUT3_CINE("S-Log3 / S-Gamut3.Cine"),
    SLOG3_SGAMUT3("S-Log3 / S-Gamut3"),
    APPLE_LOG_REC2020("Apple Log / Rec.2020");

    public final String label;

    Input(String label) {
      this.label = label;
    }
  }

  /** Fujifilm pack entry for an F-Log2 (not F-Log2 C) 33-point LUT; group 1 = film simulation. */
  public static final Pattern FUJI_FLOG2_33 =
      Pattern.compile("(?i)(?:^|/)FLog2_to_([A-Za-z0-9.+\\-]+?)_33grid[^/]*\\.cube$");
  /**
   * Sony's s709 from s709Cubes.zip: SL3SG3Ctos709.cube (S-Log3 / S-Gamut3.Cine, group 1 = "C") or
   * SL3SG3tos709.cube (S-Log3 / S-Gamut3). Not the P3 or other Sony conversions.
   */
  public static final Pattern SONY_S709 = Pattern.compile("(?i)(?:^|/)SL3SG3(C?)tos709\\.cube$");
  private static final int MAGIC = 0x474c5554; // "GLUT"
  public final int size;
  public final float[] data;
  public final String title, model, gamut;
  public final Input input;
  /** BT.2020 / F-Gamut to the table's gamut (row-major), or null for an F-Log2 / F-Gamut table. */
  private final float[] toInput;

  public CubeLut(int size, float[] data, String title, String model, String gamut) {
    this(size, data, title, model, gamut, Input.FLOG2_FGAMUT);
  }

  private CubeLut(int size, float[] data, String title, String model, String gamut, Input input) {
    if (size < 2 || data.length != size * size * size * 3)
      throw new IllegalArgumentException("Bad LUT dimensions");
    this.size = size;
    this.data = data;
    this.title = title == null ? "" : title;
    this.model = model == null ? "" : model;
    this.gamut = gamut == null ? "" : gamut;
    this.input = input;
    double[] m =
        input == Input.SLOG3_SGAMUT3_CINE
            ? SLog3.REC2020_TO_SGAMUT3_CINE
            : input == Input.SLOG3_SGAMUT3 ? SLog3.REC2020_TO_SGAMUT3 : null;
    if (m == null) toInput = null;
    else {
      toInput = new float[9];
      for (int i = 0; i < 9; i++) toInput[i] = (float) m[i];
    }
  }

  /** The same table (shared data) read as expecting {@code input} codes. */
  public CubeLut withInput(Input input) {
    return input == this.input ? this : new CubeLut(size, data, title, model, gamut, input);
  }

  /** The same table with new descriptive strings (shared data, same input). */
  public CubeLut describedAs(String title, String model, String gamut) {
    return new CubeLut(size, data, title, model, gamut, input);
  }

  /** F-Log2 code (0..1, 4096 steps) to scene-linear, for converting samples to another input. */
  private static final float[] FLOG2_LINEAR = new float[4097];

  static {
    for (int i = 0; i <= 4096; i++) FLOG2_LINEAR[i] = (float) FLog2.decode(i / 4096.0);
  }

  static float flog2Linear(float code) {
    float x = (code <= 0 ? 0 : code >= 1 ? 1 : code) * 4096;
    int i = (int) x;
    if (i >= 4096) return FLOG2_LINEAR[4096];
    return FLOG2_LINEAR[i] + (x - i) * (FLOG2_LINEAR[i + 1] - FLOG2_LINEAR[i]);
  }

  public static CubeLut parse(Reader reader) throws IOException {
    BufferedReader in = new BufferedReader(reader, 1 << 16);
    int size = 0, n = 0;
    float[] data = null;
    String title = "", model = "", gamut = "", line;
    float[] min = {0, 0, 0}, max = {1, 1, 1};
    while ((line = in.readLine()) != null) {
      line = line.trim();
      if (line.isEmpty()) continue;
      if (line.charAt(0) == '#') {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("#model:")) model = line.substring(7).trim();
        else if (lower.startsWith("#gamut:")) gamut = line.substring(7).trim();
        continue;
      }
      char c0 = line.charAt(0);
      if (Character.isLetter(c0)) {
        String[] t = line.split("\\s+");
        switch (t[0]) {
          case "TITLE":
            title = line.substring(5).trim().replace("\"", "");
            break;
          case "LUT_3D_SIZE":
            size = Integer.parseInt(t[1]);
            if (size < 2 || size > 129) throw new IOException("Unsupported LUT size " + size);
            data = new float[size * size * size * 3];
            break;
          case "LUT_1D_SIZE":
            throw new IOException("1-D LUTs are not supported");
          case "DOMAIN_MIN":
            for (int i = 0; i < 3; i++) min[i] = Float.parseFloat(t[i + 1]);
            break;
          case "DOMAIN_MAX":
            for (int i = 0; i < 3; i++) max[i] = Float.parseFloat(t[i + 1]);
            break;
          default:
            break;
        }
        continue;
      }
      if (data == null) throw new IOException("LUT data before LUT_3D_SIZE");
      if (n + 3 > data.length) throw new IOException("Too many LUT entries");
      int p = 0, len = line.length();
      for (int k = 0; k < 3; k++) {
        while (p < len && Character.isWhitespace(line.charAt(p))) p++;
        int s = p;
        while (p < len && !Character.isWhitespace(line.charAt(p))) p++;
        if (s == p) throw new IOException("Malformed LUT line: " + line);
        data[n++] = Float.parseFloat(line.substring(s, p));
      }
    }
    if (data == null || n != data.length)
      throw new IOException(
          "Incomplete LUT: " + (n / 3) + " of " + (data == null ? 0 : data.length / 3) + " entries");
    for (int i = 0; i < 3; i++)
      if (Math.abs(min[i]) > 1e-6 || Math.abs(max[i] - 1) > 1e-6)
        throw new IOException("Unsupported LUT input domain");
    return new CubeLut(size, data, title, model, gamut);
  }

  /**
   * Trilinear sample at F-Log2 / F-Gamut codes; inputs are clamped to 0..1. A table for another
   * input first converts the codes exactly (see {@link Input}).
   */
  public void sample(float r, float g, float b, float[] out) {
    float[] t = toInput;
    if (t != null) {
      float lr = flog2Linear(r), lg = flog2Linear(g), lb = flog2Linear(b);
      r = (float) SLog3.encode(t[0] * lr + t[1] * lg + t[2] * lb);
      g = (float) SLog3.encode(t[3] * lr + t[4] * lg + t[5] * lb);
      b = (float) SLog3.encode(t[6] * lr + t[7] * lg + t[8] * lb);
    } else if (input == Input.APPLE_LOG_REC2020) {
      r = (float) AppleRender.appleLogEncode(flog2Linear(r));
      g = (float) AppleRender.appleLogEncode(flog2Linear(g));
      b = (float) AppleRender.appleLogEncode(flog2Linear(b));
    }
    int n1 = size - 1;
    float x = (r <= 0 ? 0 : r >= 1 ? 1 : r) * n1;
    float y = (g <= 0 ? 0 : g >= 1 ? 1 : g) * n1;
    float z = (b <= 0 ? 0 : b >= 1 ? 1 : b) * n1;
    int x0 = (int) x, y0 = (int) y, z0 = (int) z;
    if (x0 >= n1) x0 = n1 - 1;
    if (y0 >= n1) y0 = n1 - 1;
    if (z0 >= n1) z0 = n1 - 1;
    float fx = x - x0, fy = y - y0, fz = z - z0;
    int sy = 3 * size, sz = 3 * size * size;
    int i = z0 * sz + y0 * sy + x0 * 3;
    float[] d = data;
    for (int c = 0; c < 3; c++, i++) {
      float c000 = d[i], c100 = d[i + 3], c010 = d[i + sy], c110 = d[i + sy + 3];
      float c001 = d[i + sz], c101 = d[i + sz + 3], c011 = d[i + sz + sy], c111 = d[i + sz + sy + 3];
      float c00 = c000 + fx * (c100 - c000), c10 = c010 + fx * (c110 - c010);
      float c01 = c001 + fx * (c101 - c001), c11 = c011 + fx * (c111 - c011);
      float c0 = c00 + fy * (c10 - c00), c1 = c01 + fy * (c11 - c01);
      out[c] = c0 + fz * (c1 - c0);
    }
  }

  public void write(DataOutputStream out) throws IOException {
    out.writeInt(MAGIC);
    out.writeInt(1);
    out.writeInt(size);
    out.writeUTF(title);
    out.writeUTF(model);
    out.writeUTF(gamut);
    for (float v : data) out.writeFloat(v);
  }

  public static CubeLut read(DataInputStream in) throws IOException {
    if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Not a Grain LUT file");
    int size = in.readInt();
    if (size < 2 || size > 129) throw new IOException("Bad LUT size");
    String title = in.readUTF(), model = in.readUTF(), gamut = in.readUTF();
    float[] data = new float[size * size * size * 3];
    for (int i = 0; i < data.length; i++) data[i] = in.readFloat();
    return new CubeLut(size, data, title, model, gamut);
  }
}
