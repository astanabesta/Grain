package dev.grain.photo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Display P3 wide-gamut tagging, Android 14+ (API 34) ISO 21496-1 / Ultra HDR {@link Gainmap}
 * attachment, aspect-crop gainmap preservation, and post-{@code ExifInterface} CIPA DC-007 MPF
 * directory repair.
 */
public final class UltraHdrJpeg {
  private static final int TAG_NUMBER_OF_IMAGES = 0xB001;
  private static final int TAG_MP_ENTRY = 0xB002;

  private UltraHdrJpeg() {}

  /**
   * Tags an {@code ARGB_8888} bitmap whose pixel bytes already hold Display P3 transfer-encoded
   * values as {@link ColorSpace.Named#DISPLAY_P3} in-place without converting pixel code values.
   */
  public static void tagDisplayP3(Bitmap bitmap) {
    if (bitmap == null) return;
    ColorSpace p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
    if (p3 != null) {
      bitmap.setColorSpace(p3);
    }
  }

  /**
   * Attaches a single-channel quarter-resolution {@link Gainmap} on Android 14+ (API 34+) when
   * {@code gainmapBytes} is provided. Returns the backing {@code ALPHA_8} bitmap so the caller can
   * recycle it after {@code Bitmap.compress(JPEG, ...)} completes (or {@code null} below API 34).
   */
  public static Bitmap attachGainmapIfSupported(
      Bitmap sdrBitmap, byte[] gainmapBytes, int qW, int qH, float maxHdrRatio) {
    if (sdrBitmap == null || gainmapBytes == null || qW <= 0 || qH <= 0) return null;
    if (gainmapBytes.length < qW * qH) return null;
    if (Build.VERSION.SDK_INT < 34) return null;
    return Api34.attach(sdrBitmap, gainmapBytes, qW, qH, maxHdrRatio);
  }

  /**
   * Copies and crops any Ultra HDR {@link Gainmap} from {@code src} onto {@code cropped} when an
   * aspect crop ({@code 3:2}, {@code 16:9}, {@code 1:1}, {@code XPAN}) is applied in {@link
   * PhotoStore}.
   */
  public static void preserveGainmapOnCrop(
      Bitmap src, Bitmap cropped, int cropX, int cropY, int cropW, int cropH) {
    if (src == null || cropped == null || Build.VERSION.SDK_INT < 34) return;
    Api34.cropGainmap(src, cropped, src.getWidth(), src.getHeight(), cropX, cropY, cropW, cropH);
  }

  /**
   * Rotates/flips any Ultra HDR {@link Gainmap} from {@code src} onto {@code dst} when {@link
   * PhotoStore} applies an EXIF orientation matrix for thumbnails or playback.
   */
  public static void preserveGainmapOnTransform(Bitmap src, Bitmap dst, Matrix m) {
    if (src == null || dst == null || src == dst || m == null || Build.VERSION.SDK_INT < 34) return;
    Api34.transformGainmap(src, dst, m);
  }

  /**
   * Preserves Ultra HDR {@link Gainmap} on the {@code BitmapRegionDecoder} path ({@code > 20 MP}
   * aspect crops) by decoding a heavily subsampled probe ({@code inSampleSize = 8}) of {@code
   * source} to retrieve its gainmap without allocating the full-resolution bitmap.
   */
  public static void preserveGainmapFromFileOnSensorCrop(
      File source, Bitmap cropped, int srcW, int srcH, int[] sensorCrop) {
    if (source == null
        || cropped == null
        || sensorCrop == null
        || Build.VERSION.SDK_INT < 34) return;
    BitmapFactory.Options po = new BitmapFactory.Options();
    po.inSampleSize = 8;
    po.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap probe = BitmapFactory.decodeFile(source.getPath(), po);
    if (probe == null) return;
    try {
      Api34.cropGainmap(
          probe, cropped, srcW, srcH, sensorCrop[0], sensorCrop[1], sensorCrop[2], sensorCrop[3]);
    } finally {
      probe.recycle();
    }
  }

  /**
   * Repairs the CIPA DC-007 Multi-Picture Format ({@code APP2 "MPF\0"}) Index IFD in-place after
   * {@code ExifInterface.saveAttributes()} rewrites {@code APP1 EXIF}.
   *
   * <p>{@code ExifInterface} inserts or resizes {@code APP1} without updating the 4-byte primary
   * image byte length in MP Entry 0 (or MP Entry 1's offset if {@code APP1} shifted relative to
   * {@code APP2}). Using {@link RandomAccessFile}, this scans only the primary JPEG marker header,
   * locates the {@code FF D9 FF D8} boundary before the gainmap JPEG, and patches the 8 bytes in
   * place with zero multi-megabyte heap allocation.
   *
   * @return {@code true} if an MPF directory was found and verified/patched, {@code false} if the
   *     file is a standard single-image JPEG.
   */
  public static boolean repairMpfAfterExifSave(File jpegFile) throws IOException {
    if (jpegFile == null || !jpegFile.isFile()) return false;
    try (RandomAccessFile raf = new RandomAccessFile(jpegFile, "rw")) {
      long fileLen = raf.length();
      if (fileLen < 32) return false;
      if (raf.readUnsignedByte() != 0xFF || raf.readUnsignedByte() != 0xD8) return false;

      long pos = 2;
      // Scan primary JPEG marker segments up to SOS (FF DA)
      while (pos + 4 <= fileLen && pos < 512 * 1024L) {
        raf.seek(pos);
        int b0 = raf.readUnsignedByte();
        if (b0 != 0xFF) break;
        int marker = raf.readUnsignedByte();
        while (marker == 0xFF && raf.getFilePointer() < fileLen) {
          marker = raf.readUnsignedByte();
        }
        long segLenPos = raf.getFilePointer();
        if (marker == 0xDA || marker == 0xD9) break; // SOS or EOI
        if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
          pos = segLenPos;
          continue;
        }
        if (segLenPos + 2 > fileLen) break;
        int segLen = raf.readUnsignedShort();
        if (segLen < 2 || segLenPos + segLen > fileLen) break;

        if (marker == 0xE2 && segLen >= 24) {
          int m0 = raf.readUnsignedByte();
          int m1 = raf.readUnsignedByte();
          int m2 = raf.readUnsignedByte();
          int m3 = raf.readUnsignedByte();
          if (m0 == 'M' && m1 == 'P' && m2 == 'F' && m3 == 0) {
            long tiffBase = segLenPos + 2 + 4;
            int segEnd = (int) Math.min(segLen - 6, 4096);
            byte[] buf = new byte[segEnd];
            raf.seek(tiffBase);
            raf.readFully(buf);
            return patchMpfPayload(raf, fileLen, tiffBase, buf);
          }
        }
        pos = segLenPos + segLen;
      }
      return false;
    }
  }

  private static boolean patchMpfPayload(
      RandomAccessFile raf, long fileLen, long tiffBase, byte[] buf) throws IOException {
    if (buf.length < 16) return false;
    boolean le;
    if (buf[0] == 'I' && buf[1] == 'I' && (buf[2] & 0xFF) == 0x2A && (buf[3] & 0xFF) == 0x00) {
      le = true;
    } else if (buf[0] == 'M'
        && buf[1] == 'M'
        && (buf[2] & 0xFF) == 0x00
        && (buf[3] & 0xFF) == 0x2A) {
      le = false;
    } else {
      return false;
    }

    int ifdOffset = readInt32(buf, 4, le);
    if (ifdOffset < 8 || ifdOffset + 2 > buf.length) return false;
    int entryCount = readUInt16(buf, ifdOffset, le);
    if (entryCount <= 0 || entryCount > 64) return false;
    if (ifdOffset + 2 + entryCount * 12 > buf.length) return false;

    int numImages = -1;
    int mpEntryOffset = -1;
    for (int i = 0; i < entryCount; i++) {
      int eOff = ifdOffset + 2 + i * 12;
      int tag = readUInt16(buf, eOff, le);
      int count = readInt32(buf, eOff + 4, le);
      int valOrOff = readInt32(buf, eOff + 8, le);
      if (tag == TAG_NUMBER_OF_IMAGES) {
        numImages = valOrOff;
      } else if (tag == TAG_MP_ENTRY && count >= 32) {
        mpEntryOffset = valOrOff;
      }
    }
    if (numImages < 2 || mpEntryOffset < 8 || mpEntryOffset + 32 > buf.length) return false;

    int primarySizeOld = readInt32(buf, mpEntryOffset + 4, le);
    int gainmapSize = readInt32(buf, mpEntryOffset + 16 + 4, le);
    int gainmapRelOld = readInt32(buf, mpEntryOffset + 16 + 8, le);
    if (gainmapSize <= 4 || gainmapSize >= fileLen) return false;

    // Locate the verified FF D9 (primary EOI) + FF D8 (gainmap SOI) boundary
    long candidateA = tiffBase + (gainmapRelOld & 0xFFFFFFFFL);
    long candidateB = fileLen - (gainmapSize & 0xFFFFFFFFL);
    long actualGainmapStart = -1;
    if (isPrimaryToSecondaryBoundary(raf, fileLen, candidateB)) {
      actualGainmapStart = candidateB;
    } else if (isPrimaryToSecondaryBoundary(raf, fileLen, candidateA)) {
      actualGainmapStart = candidateA;
    }
    if (actualGainmapStart <= tiffBase || actualGainmapStart > 0x7FFFFFFFL) return false;

    int primarySizeNew = (int) actualGainmapStart;
    int gainmapRelNew = (int) (actualGainmapStart - tiffBase);
    if (primarySizeNew == primarySizeOld && gainmapRelNew == gainmapRelOld) return true;

    byte[] four = new byte[4];
    writeInt32(four, 0, primarySizeNew, le);
    raf.seek(tiffBase + mpEntryOffset + 4);
    raf.write(four);

    if (gainmapRelNew != gainmapRelOld) {
      writeInt32(four, 0, gainmapRelNew, le);
      raf.seek(tiffBase + mpEntryOffset + 16 + 8);
      raf.write(four);
    }
    return true;
  }

  private static boolean isPrimaryToSecondaryBoundary(
      RandomAccessFile raf, long fileLen, long gainmapStart) throws IOException {
    if (gainmapStart < 4 || gainmapStart + 2 > fileLen) return false;
    raf.seek(gainmapStart - 2);
    int p0 = raf.readUnsignedByte();
    int p1 = raf.readUnsignedByte();
    int s0 = raf.readUnsignedByte();
    int s1 = raf.readUnsignedByte();
    return p0 == 0xFF && p1 == 0xD9 && s0 == 0xFF && s1 == 0xD8;
  }

  private static int readUInt16(byte[] b, int off, boolean le) {
    return le
        ? ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8))
        : (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
  }

  private static int readInt32(byte[] b, int off, boolean le) {
    return le
        ? ((b[off] & 0xFF)
            | ((b[off + 1] & 0xFF) << 8)
            | ((b[off + 2] & 0xFF) << 16)
            | ((b[off + 3] & 0xFF) << 24))
        : (((b[off] & 0xFF) << 24)
            | ((b[off + 1] & 0xFF) << 16)
            | ((b[off + 2] & 0xFF) << 8)
            | (b[off + 3] & 0xFF));
  }

  private static void writeInt32(byte[] b, int off, int v, boolean le) {
    if (le) {
      b[off] = (byte) v;
      b[off + 1] = (byte) (v >>> 8);
      b[off + 2] = (byte) (v >>> 16);
      b[off + 3] = (byte) (v >>> 24);
    } else {
      b[off] = (byte) (v >>> 24);
      b[off + 1] = (byte) (v >>> 16);
      b[off + 2] = (byte) (v >>> 8);
      b[off + 3] = (byte) v;
    }
  }

  /** Isolated API 34+ class so Android 10-13 Dalvik/ART verifiers never resolve {@link Gainmap}. */
  private static final class Api34 {
    static Bitmap attach(
        Bitmap sdrBitmap, byte[] gainmapBytes, int qW, int qH, float maxHdrRatio) {
      Bitmap gmBmp = Bitmap.createBitmap(qW, qH, Bitmap.Config.ALPHA_8);
      gmBmp.copyPixelsFromBuffer(ByteBuffer.wrap(gainmapBytes, 0, qW * qH));
      Gainmap gm = new Gainmap(gmBmp);
      float eps = 1f / 64f;
      gm.setRatioMin(1.0f, 1.0f, 1.0f);
      gm.setRatioMax(maxHdrRatio, maxHdrRatio, maxHdrRatio);
      gm.setGamma(1.0f, 1.0f, 1.0f);
      gm.setEpsilonSdr(eps, eps, eps);
      gm.setEpsilonHdr(eps, eps, eps);
      gm.setMinDisplayRatioForHdrTransition(1.0f);
      gm.setDisplayRatioForFullHdr(maxHdrRatio);
      sdrBitmap.setGainmap(gm);
      return gmBmp;
    }

    static void cropGainmap(
        Bitmap src,
        Bitmap cropped,
        int fullSrcW,
        int fullSrcH,
        int cropX,
        int cropY,
        int cropW,
        int cropH) {
      if (!src.hasGainmap()) return;
      Gainmap gm = src.getGainmap();
      if (gm == null) return;
      Bitmap contents = gm.getGainmapContents();
      if (contents == null || contents.getWidth() <= 0 || contents.getHeight() <= 0) return;

      int srcW = Math.max(1, fullSrcW);
      int srcH = Math.max(1, fullSrcH);
      int gw = contents.getWidth();
      int gh = contents.getHeight();

      int gx = Math.max(0, Math.min(gw - 1, Math.round((float) cropX * gw / srcW)));
      int gy = Math.max(0, Math.min(gh - 1, Math.round((float) cropY * gh / srcH)));
      int gcw = Math.max(1, Math.min(gw - gx, Math.round((float) cropW * gw / srcW)));
      int gch = Math.max(1, Math.min(gh - gy, Math.round((float) cropH * gh / srcH)));

      Bitmap croppedGmBmp = Bitmap.createBitmap(contents, gx, gy, gcw, gch);
      Gainmap next = new Gainmap(croppedGmBmp);
      float[] rMin = gm.getRatioMin();
      float[] rMax = gm.getRatioMax();
      float[] gamma = gm.getGamma();
      float[] eSdr = gm.getEpsilonSdr();
      float[] eHdr = gm.getEpsilonHdr();
      next.setRatioMin(rMin[0], rMin[1], rMin[2]);
      next.setRatioMax(rMax[0], rMax[1], rMax[2]);
      next.setGamma(gamma[0], gamma[1], gamma[2]);
      next.setEpsilonSdr(eSdr[0], eSdr[1], eSdr[2]);
      next.setEpsilonHdr(eHdr[0], eHdr[1], eHdr[2]);
      next.setMinDisplayRatioForHdrTransition(gm.getMinDisplayRatioForHdrTransition());
      next.setDisplayRatioForFullHdr(gm.getDisplayRatioForFullHdr());
      cropped.setGainmap(next);
    }

    static void transformGainmap(Bitmap src, Bitmap dst, Matrix m) {
      if (!src.hasGainmap()) return;
      Gainmap gm = src.getGainmap();
      if (gm == null) return;
      Bitmap contents = gm.getGainmapContents();
      if (contents == null || contents.getWidth() <= 0 || contents.getHeight() <= 0) return;
      Bitmap rotGmBmp =
          Bitmap.createBitmap(contents, 0, 0, contents.getWidth(), contents.getHeight(), m, true);
      Gainmap next = new Gainmap(rotGmBmp);
      float[] rMin = gm.getRatioMin();
      float[] rMax = gm.getRatioMax();
      float[] gamma = gm.getGamma();
      float[] eSdr = gm.getEpsilonSdr();
      float[] eHdr = gm.getEpsilonHdr();
      next.setRatioMin(rMin[0], rMin[1], rMin[2]);
      next.setRatioMax(rMax[0], rMax[1], rMax[2]);
      next.setGamma(gamma[0], gamma[1], gamma[2]);
      next.setEpsilonSdr(eSdr[0], eSdr[1], eSdr[2]);
      next.setEpsilonHdr(eHdr[0], eHdr[1], eHdr[2]);
      next.setMinDisplayRatioForHdrTransition(gm.getMinDisplayRatioForHdrTransition());
      next.setDisplayRatioForFullHdr(gm.getDisplayRatioForFullHdr());
      dst.setGainmap(next);
    }
  }
}
