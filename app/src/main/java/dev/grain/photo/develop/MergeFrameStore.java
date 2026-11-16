package dev.grain.photo.develop;

import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Temporary, private spool for undeveloped linear RAW frames. A guided MERGE PANO keeps four
 * shutters apart, so holding four 36-48 MP {@code short[]} frames on the Java heap would OOM before
 * the stitch even starts. Instead each frame is streamed to one compact file as soon as it arrives
 * and read back row-range-at-a-time by {@link MergeMerger}. No DNG, JPEG, film simulation or tone
 * curve is involved: these are raw sensor numbers plus the calibration metadata the HAL published.
 *
 * <p>Pure Java (only {@code java.io} / {@code java.nio}), so the same format is exercised by JVM
 * tests. Files live in the app's cache directory and are deleted on success, failure or cancel.
 */
public final class MergeFrameStore {
  private static final int MAGIC = 0x47524D46; // "GRMF"
  private static final int VERSION = 1;
  private static final String EXT = ".grainraw";

  private MergeFrameStore() {}

  /** Writes one linear RAW frame. The pixel payload is packed (rowStride folded away). */
  public static File write(File dir, int index, RawFrame f) throws IOException {
    if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory())
      throw new IOException("Cannot create merge spool " + dir);
    File file = new File(dir, "frame-" + index + EXT);
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), 1 << 16))) {
      out.writeInt(MAGIC);
      out.writeInt(VERSION);
      out.writeInt(f.width);
      out.writeInt(f.height);
      out.writeInt(f.cfa);
      writeFloats(out, f.black);
      out.writeFloat(f.white);
      writeDoubles(out, f.neutral);
      writeDoubles(out, f.colorMatrix1);
      writeDoubles(out, f.colorMatrix2);
      writeDoubles(out, f.forwardMatrix1);
      writeDoubles(out, f.forwardMatrix2);
      writeDoubles(out, f.calibration1);
      writeDoubles(out, f.calibration2);
      writeDoubles(out, f.noiseS);
      writeDoubles(out, f.noiseO);
      out.writeInt(f.illuminant1);
      out.writeInt(f.illuminant2);
      out.writeInt(f.iso);
      out.writeLong(f.exposureNs);
      out.writeInt(f.stackCount);
      out.writeInt(f.shadingColumns);
      out.writeInt(f.shadingRows);
      out.writeInt(f.shadingLeft);
      out.writeInt(f.shadingTop);
      out.writeInt(f.shadingWidth);
      out.writeInt(f.shadingHeight);
      writeFloats(out, f.shading);
      writeInts(out, f.faces);
      writeInts(out, f.faceScores);
      out.writeUTF(f.faceSource == null ? "" : f.faceSource);
      out.writeInt(f.faceEyes);
      out.writeInt(f.faceIds);
      out.writeInt(f.faceMouths);

      byte[] row = new byte[f.width * 2];
      ShortBuffer shorts = ByteBuffer.wrap(row).order(ByteOrder.BIG_ENDIAN).asShortBuffer();
      for (int y = 0; y < f.height; y++) {
        int base = y * f.rowStride;
        for (int x = 0; x < f.width; x++) shorts.put(x, f.data[base + x]);
        out.write(row);
      }
    }
    return file;
  }

  /** Opens a spool file for streaming reads. Metadata is held in memory; pixels are read on demand. */
  public static Reader open(File file) throws IOException {
    return new Reader(file);
  }

  /** Recursively removes a merge spool directory. Safe on a missing or partially written dir. */
  public static void deleteDir(File dir) {
    if (dir == null || !dir.exists()) return;
    File[] kids = dir.listFiles();
    if (kids != null) for (File k : kids) k.delete();
    dir.delete();
  }

  /** A {@link MergeMerger.FrameSource} backed by one spool file. */
  public static final class Reader implements MergeMerger.FrameSource {
    private final File file;
    private final RandomAccessFile raf;
    private final long dataOffset;
    private final int width, height, cfa;
    private final float[] black = new float[4];
    private float white;
    private double[] neutral, colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2;
    private double[] calibration1, calibration2, noiseS, noiseO;
    private int illuminant1, illuminant2, iso, stackCount;
    private long exposureNs;
    private int shadingColumns, shadingRows, shadingLeft, shadingTop, shadingWidth, shadingHeight;
    private float[] shading;
    private int[] faces, faceScores;
    private String faceSource = "";
    private int faceEyes, faceIds, faceMouths;

    private Reader(File file) throws IOException {
      this.file = file;
      this.raf = new RandomAccessFile(file, "r");
      try {
        DataInput in = raf;
        if (in.readInt() != MAGIC || in.readInt() != VERSION)
          throw new IOException("Unsupported merge spool file " + file);
        width = in.readInt();
        height = in.readInt();
        cfa = in.readInt();
        float[] b = readFloats(in);
        if (b == null || b.length != 4) throw new IOException("Bad black level");
        System.arraycopy(b, 0, black, 0, 4);
        white = in.readFloat();
        neutral = readDoubles(in);
        colorMatrix1 = readDoubles(in);
        colorMatrix2 = readDoubles(in);
        forwardMatrix1 = readDoubles(in);
        forwardMatrix2 = readDoubles(in);
        calibration1 = readDoubles(in);
        calibration2 = readDoubles(in);
        noiseS = readDoubles(in);
        noiseO = readDoubles(in);
        illuminant1 = in.readInt();
        illuminant2 = in.readInt();
        iso = in.readInt();
        exposureNs = in.readLong();
        stackCount = in.readInt();
        shadingColumns = in.readInt();
        shadingRows = in.readInt();
        shadingLeft = in.readInt();
        shadingTop = in.readInt();
        shadingWidth = in.readInt();
        shadingHeight = in.readInt();
        shading = readFloats(in);
        faces = readInts(in);
        faceScores = readInts(in);
        faceSource = in.readUTF();
        faceEyes = in.readInt();
        faceIds = in.readInt();
        faceMouths = in.readInt();
        dataOffset = raf.getFilePointer();
      } catch (IOException e) {
        raf.close();
        throw e;
      }
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int cfa() { return cfa; }
    @Override public float[] black() { return black; }
    @Override public float white() { return white; }

    @Override
    public void copyMetadataTo(RawFrame t) {
      t.cfa = cfa;
      System.arraycopy(black, 0, t.black, 0, 4);
      t.white = white;
      t.neutral = neutral == null ? new double[] {1, 1, 1} : neutral.clone();
      t.colorMatrix1 = MergeFrameStore.copyDoubles(colorMatrix1);
      t.colorMatrix2 = MergeFrameStore.copyDoubles(colorMatrix2);
      t.forwardMatrix1 = MergeFrameStore.copyDoubles(forwardMatrix1);
      t.forwardMatrix2 = MergeFrameStore.copyDoubles(forwardMatrix2);
      t.calibration1 = MergeFrameStore.copyDoubles(calibration1);
      t.calibration2 = MergeFrameStore.copyDoubles(calibration2);
      t.illuminant1 = illuminant1;
      t.illuminant2 = illuminant2;
      t.iso = iso;
      t.exposureNs = exposureNs;
      t.noiseS = MergeFrameStore.copyDoubles(noiseS);
      t.noiseO = MergeFrameStore.copyDoubles(noiseO);
      t.shading = shading == null ? null : shading.clone();
      t.shadingColumns = shadingColumns;
      t.shadingRows = shadingRows;
      t.shadingLeft = shadingLeft;
      t.shadingTop = shadingTop;
      t.shadingWidth = shadingWidth;
      t.shadingHeight = shadingHeight;
      t.faces = faces == null ? null : faces.clone();
      t.faceScores = faceScores == null ? null : faceScores.clone();
      t.faceSource = faceSource;
      t.faceEyes = faceEyes;
      t.faceIds = faceIds;
      t.faceMouths = faceMouths;
      t.stackCount = stackCount;
    }

    @Override
    public synchronized short[] readAll() throws IOException {
      short[] dst = new short[width * height];
      readRows(0, height, dst);
      return dst;
    }

    /** Reads rows [y0,y1) into a packed destination; safe for the parallel strip tasks. */
    @Override
    public synchronized void readRows(int y0, int y1, short[] dst) throws IOException {
      if (y0 < 0 || y1 > height || y1 < y0)
        throw new IOException("Bad row range " + y0 + ".." + y1);
      int rows = y1 - y0;
      if (dst.length < rows * width) throw new IOException("Row buffer too small");
      raf.seek(dataOffset + (long) y0 * width * 2);
      byte[] row = new byte[width * 2];
      ShortBuffer shorts = ByteBuffer.wrap(row).order(ByteOrder.BIG_ENDIAN).asShortBuffer();
      for (int y = y0; y < y1; y++) {
        raf.readFully(row);
        shorts.position(0);
        shorts.get(dst, (y - y0) * width, width);
      }
    }

    @Override public void close() {
      try { raf.close(); } catch (IOException ignored) {}
    }

    @Override public String toString() { return "MergeFrameStore.Reader(" + file.getName() + ")"; }
  }

  private static double[] copyDoubles(double[] a) { return a == null ? null : a.clone(); }

  private static void writeDoubles(DataOutputStream out, double[] a) throws IOException {
    if (a == null) { out.writeInt(-1); return; }
    out.writeInt(a.length);
    for (double v : a) out.writeDouble(v);
  }

  private static double[] readDoubles(DataInput in) throws IOException {
    int n = in.readInt();
    if (n < 0) return null;
    if (n > 1_000_000) throw new IOException("Implausible double array " + n);
    double[] a = new double[n];
    for (int i = 0; i < n; i++) a[i] = in.readDouble();
    return a;
  }

  private static void writeFloats(DataOutputStream out, float[] a) throws IOException {
    if (a == null) { out.writeInt(-1); return; }
    out.writeInt(a.length);
    for (float v : a) out.writeFloat(v);
  }

  private static float[] readFloats(DataInput in) throws IOException {
    int n = in.readInt();
    if (n < 0) return null;
    if (n > 100_000_000) throw new IOException("Implausible float array " + n);
    float[] a = new float[n];
    for (int i = 0; i < n; i++) a[i] = in.readFloat();
    return a;
  }

  private static void writeInts(DataOutputStream out, int[] a) throws IOException {
    if (a == null) { out.writeInt(-1); return; }
    out.writeInt(a.length);
    for (int v : a) out.writeInt(v);
  }

  private static int[] readInts(DataInput in) throws IOException {
    int n = in.readInt();
    if (n < 0) return null;
    if (n > 10_000_000) throw new IOException("Implausible int array " + n);
    int[] a = new int[n];
    for (int i = 0; i < n; i++) a[i] = in.readInt();
    return a;
  }
}
