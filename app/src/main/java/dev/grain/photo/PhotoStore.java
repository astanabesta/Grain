package dev.grain.photo;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Private files first, explicit user export to MediaStore. No broad media permission. */
public final class PhotoStore {
  public static final class Photo {
    public final String id;
    public final File image, original, receipt;
    /** RAW recipes only: the sensor DNG and the phone's own JPEG of the same exposure. */
    public final File dng, phoneJpeg;
    public final JSONObject metadata;

    Photo(String id, File image, File original, File receipt, File dng, File phoneJpeg, JSONObject metadata) {
      this.id = id;
      this.image = image;
      this.original = original;
      this.receipt = receipt;
      this.dng = dng;
      this.phoneJpeg = phoneJpeg;
      this.metadata = metadata;
    }

    public boolean raw() {
      return metadata.optBoolean("raw", false);
    }
  }

  private final Context context;
  private final File root;

  public PhotoStore(Context context) {
    this.context = context;
    root = new File(context.getFilesDir(), "photos");
    if (!root.exists() && !root.mkdirs())
      throw new IllegalStateException("Photo directory unavailable");
  }

  public Photo save(byte[] jpeg, String ratio, double zoom, boolean keepFull, JSONObject metadata)
      throws Exception {
    return save(jpeg, ratio, zoom, keepFull, metadata, null, null);
  }

  /** Saves a photo; for RAW recipes also keeps the DNG (moved in) and the phone's reference JPEG. */
  public Photo save(
      byte[] jpeg,
      String ratio,
      double zoom,
      boolean keepFull,
      JSONObject metadata,
      File dng,
      byte[] phoneJpeg)
      throws Exception {
    return saveInternal(jpeg, null, ratio, zoom, keepFull, metadata, dng, phoneJpeg);
  }

  /** Saves a developed JPEG file without loading its compressed bytes onto the Java heap. */
  public Photo saveFile(
      File jpegFile,
      String ratio,
      double zoom,
      boolean keepFull,
      JSONObject metadata,
      File dng,
      byte[] phoneJpeg)
      throws Exception {
    return saveInternal(null, jpegFile, ratio, zoom, keepFull, metadata, dng, phoneJpeg);
  }

  @SuppressWarnings("deprecation")
  private Photo saveInternal(
      byte[] jpeg,
      File jpegFile,
      String ratio,
      double zoom,
      boolean keepFull,
      JSONObject metadata,
      File dng,
      byte[] phoneJpeg)
      throws Exception {
    String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    File staging = new File(root, "." + id);
    if (!staging.mkdir()) throw new IOException("Cannot start photo save");
    if (dng != null && dng.isFile()) {
      File target = new File(staging, "sensor.dng");
      if (!dng.renameTo(target)) {
        copy(dng, target);
        if (!dng.delete()) android.util.Log.w("Grain", "Temp DNG kept");
      }
      metadata.put("dngKept", true);
    }
    if (phoneJpeg != null) {
      try (FileOutputStream out = new FileOutputStream(new File(staging, "phone-isp.jpg"))) {
        out.write(phoneJpeg);
        out.getFD().sync();
      }
      metadata.put("phoneJpegKept", true);
      addRotationQuietly(new File(staging, "phone-isp.jpg"), metadata.optInt("extraRotation", 0));
    }
    boolean rawPhoto = metadata.optBoolean("raw", false);
    try {
      File source = new File(staging, "capture.jpg");
      if (jpegFile != null) {
        if (!jpegFile.renameTo(source)) {
          copy(jpegFile, source);
          if (!jpegFile.delete() && jpegFile.exists()) {
            android.util.Log.w("Grain", "Temp developed JPEG kept");
          }
        }
      } else {
        try (FileOutputStream out = new FileOutputStream(source)) {
          out.write(jpeg);
          out.getFD().sync();
        }
      }
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(source.getPath(), bounds);
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
        throw new IOException("Invalid JPEG from camera");
      ExifInterface exif = new ExifInterface(source);
      int orientation =
          exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
      int uprightW = (orientation >= 5 && orientation <= 8) ? bounds.outHeight : bounds.outWidth;
      int uprightH = (orientation >= 5 && orientation <= 8) ? bounds.outWidth : bounds.outHeight;
      int[] crop = Framing.crop(uprightW, uprightH, ratio, zoom);
      boolean full = crop[0] == 0 && crop[1] == 0 && crop[2] == uprightW && crop[3] == uprightH;
      File finalImage = new File(staging, "photo.jpg");
      if (full) {
        copy(source, finalImage);
        JSONObject recObj = metadata.optJSONObject("recipe");
        String recTitle = recObj == null ? "" : recObj.optString("name", "");
        if (!recTitle.isEmpty()) {
          try {
            ExifInterface output = new ExifInterface(finalImage);
            if (output.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) == null) {
              output.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, exifAscii(recTitle + " · Grain Photo"));
            }
            output.setAttribute(ExifInterface.TAG_USER_COMMENT, exifAscii("Grain Photo · " + recTitle));
            output.saveAttributes();
            UltraHdrJpeg.repairMpfAfterExifSave(finalImage);
          } catch (Exception ignored) {
          }
        }
      } else {
        int[] sensorCrop = Framing.sensorCropRect(bounds.outWidth, bounds.outHeight, orientation, crop);
        boolean regionCrop =
            sensorCrop != null && (long) bounds.outWidth * bounds.outHeight > 20_000_000L;
        Bitmap decoded = null, upright = null, cropped = null;
        String savedOrientation = "1";
        try {
          if (regionCrop) {
            BitmapRegionDecoder rd = BitmapRegionDecoder.newInstance(source.getPath(), false);
            try {
              Rect r =
                  new Rect(
                      sensorCrop[0],
                      sensorCrop[1],
                      sensorCrop[0] + sensorCrop[2],
                      sensorCrop[1] + sensorCrop[3]);
              BitmapFactory.Options ro = new BitmapFactory.Options();
              ro.inPreferredConfig = Bitmap.Config.ARGB_8888;
              cropped = rd.decodeRegion(r, ro);
            } finally {
              rd.recycle();
            }
            if (cropped == null) throw new IOException("Cannot region-decode JPEG");
            UltraHdrJpeg.preserveGainmapFromFileOnSensorCrop(
                source, cropped, bounds.outWidth, bounds.outHeight, sensorCrop);
            savedOrientation = String.valueOf(orientation == 0 ? 1 : orientation);
          } else if (sensorCrop != null) {
            decoded = BitmapFactory.decodeFile(source.getPath());
            if (decoded == null) throw new IOException("Cannot decode JPEG");
            cropped =
                Bitmap.createBitmap(
                    decoded, sensorCrop[0], sensorCrop[1], sensorCrop[2], sensorCrop[3]);
            UltraHdrJpeg.preserveGainmapOnCrop(
                decoded, cropped, sensorCrop[0], sensorCrop[1], sensorCrop[2], sensorCrop[3]);
            decoded.recycle();
            decoded = null;
            savedOrientation = String.valueOf(orientation == 0 ? 1 : orientation);
          } else {
            decoded = BitmapFactory.decodeFile(source.getPath());
            if (decoded == null) throw new IOException("Cannot decode JPEG");
            Matrix m = orientationMatrix(orientation);
            upright =
                Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
            if (upright != decoded) {
              UltraHdrJpeg.preserveGainmapOnTransform(decoded, upright, m);
              decoded.recycle();
              decoded = null;
            }
            cropped = Bitmap.createBitmap(upright, crop[0], crop[1], crop[2], crop[3]);
            UltraHdrJpeg.preserveGainmapOnCrop(
                upright, cropped, crop[0], crop[1], crop[2], crop[3]);
          }
          try (OutputStream out = new BufferedOutputStream(new FileOutputStream(finalImage), 65536)) {
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 97, out))
              throw new IOException("JPEG export failed");
          }
          cropped.recycle();
          cropped = null;
          ExifInterface output = new ExifInterface(finalImage);
          String[] tags = {
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_USER_COMMENT
          };
          for (String tag : tags) {
            String value = exif.getAttribute(tag);
            if (value != null) output.setAttribute(tag, value);
          }
          JSONObject recObj = metadata.optJSONObject("recipe");
          String recTitle = recObj == null ? "" : recObj.optString("name", "");
          if (!recTitle.isEmpty()) {
            if (output.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) == null) {
              output.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, exifAscii(recTitle + " · Grain Photo"));
            }
            output.setAttribute(ExifInterface.TAG_USER_COMMENT, exifAscii("Grain Photo · " + recTitle));
          }
          output.setAttribute(ExifInterface.TAG_ORIENTATION, savedOrientation);
          output.setAttribute(ExifInterface.TAG_SOFTWARE, RawDevelopment.SOFTWARE);
          output.saveAttributes();
          UltraHdrJpeg.repairMpfAfterExifSave(finalImage);
        } finally {
          if (cropped != null && !cropped.isRecycled()) cropped.recycle();
          if (upright != null && !upright.isRecycled()) upright.recycle();
          if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
      }
      int extra = metadata.optInt("extraRotation", 0);
      addRotationQuietly(finalImage, extra);
      boolean turned = extra == 90 || extra == 270;
      metadata
          .put("id", id)
          .put("width", turned ? crop[3] : crop[2])
          .put("height", turned ? crop[2] : crop[3])
          .put("sourceWidth", turned ? uprightH : uprightW)
          .put("sourceHeight", turned ? uprightW : uprightH)
          .put("ratio", ratio)
          .put("digitalZoom", zoom)
          .put("fullCaptureKept", keepFull)
          .put(
              "originalMeaning",
              rawPhoto
                  ? "Full unzoomed Grain development of the RAW (recipe applied). The sensor RAW is"
                      + " sensor.dng when kept"
                  : "Full unzoomed ISP-recipe JPEG, NOT unfiltered and NOT sensor RAW")
          .put("geometryOnlyPostProcessing", !full)
          .put("savedAt", System.currentTimeMillis());
      if (keepFull) {
        File kept = new File(staging, "full-capture.jpg");
        if (!source.renameTo(kept)) throw new IOException("Cannot retain source");
        addRotationQuietly(kept, extra);
      } else if (!source.delete()) throw new IOException("Cannot remove staging source");
      File receipt = new File(staging, "receipt.json");
      try (FileOutputStream out = new FileOutputStream(receipt)) {
        out.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      File complete = new File(root, id);
      if (!staging.renameTo(complete)) throw new IOException("Cannot commit photo");
      return read(complete);
    } catch (Throwable error) {
      // A failed geometry crop must not discard a valid full-resolution capture.
      File source = new File(staging, "capture.jpg");
      if (source.isFile() && source.length() > 0)
        try {
          BitmapFactory.Options bounds = new BitmapFactory.Options();
          bounds.inJustDecodeBounds = true;
          BitmapFactory.decodeFile(source.getPath(), bounds);
          if (bounds.outWidth < 1 || bounds.outHeight < 1)
            throw new IOException("Source JPEG incomplete");
          ExifInterface exif = new ExifInterface(source);
          int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
          int w = o >= 5 && o <= 8 ? bounds.outHeight : bounds.outWidth,
              h = o >= 5 && o <= 8 ? bounds.outWidth : bounds.outHeight;
          File fallback = new File(staging, "photo.jpg");
          if (fallback.exists() && !fallback.delete())
            throw new IOException("Cannot replace failed crop");
          if (!source.renameTo(fallback)) throw new IOException("Cannot recover source capture");
          metadata
              .put("id", id)
              .put("width", w)
              .put("height", h)
              .put("sourceWidth", w)
              .put("sourceHeight", h)
              .put("ratio", "FULL")
              .put("requestedRatio", ratio)
              .put("digitalZoom", 1)
              .put("requestedDigitalZoom", zoom)
              .put("recoveredFullFrame", true)
              .put("fullCaptureKept", false)
              .put("geometryOnlyPostProcessing", false)
              .put("processingError", error.getClass().getSimpleName() + ": " + error.getMessage())
              .put("savedAt", System.currentTimeMillis());
          File receipt = new File(staging, "receipt.json");
          try (FileOutputStream out = new FileOutputStream(receipt)) {
            out.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
          }
          File complete = new File(root, id);
          if (!staging.renameTo(complete)) throw new IOException("Cannot commit recovered capture");
          return read(complete);
        } catch (Exception recoveryFailure) {
          throw new IOException(
              "Save/crop failed; capture staging retained at " + staging.getName(), error);
        }
      if (error instanceof Exception) throw (Exception) error;
      throw new IOException(error);
    }
  }

  /**
   * Adds a clockwise rotation (0, 90, 180 or 270 degrees: the phone held sideways or upside down)
   * to a JPEG's EXIF orientation in place. Pixels are not touched; mirrored values are left alone.
   */
  static void addRotation(File jpeg, int degrees) throws IOException {
    degrees = ((degrees % 360) + 360) % 360;
    if (degrees % 90 != 0 || degrees == 0 || !jpeg.isFile()) return;
    ExifInterface exif = new ExifInterface(jpeg);
    int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
    int idx = rotationIndex(o);
    if (idx < 0) return;
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(CLOCKWISE[(idx + degrees / 90) % 4]));
    exif.saveAttributes();
    UltraHdrJpeg.repairMpfAfterExifSave(jpeg);
  }

  /** Orientation is metadata only: a failure to update it must never lose the photo. */
  static void addRotationQuietly(File jpeg, int degrees) {
    try {
      addRotation(jpeg, degrees);
    } catch (Exception e) {
      android.util.Log.w("Grain", "Could not set orientation on " + jpeg.getName(), e);
    }
  }

  /**
   * EXIF ASCII-type fields (and ASCII-prefixed UserComment) mangle non-ASCII characters to '?';
   * map common typography to clean ASCII equivalents so recipe names survive intact.
   */
  static String exifAscii(String s) {
    if (s == null) return null;
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '·' || c == '–' || c == '—' || c == '―') b.append('-');
      else if (c == '“' || c == '”' || c == '„') b.append('"');
      else if (c == '‘' || c == '’' || c == '‚') b.append('\'');
      else if (c >= 0x20 && c < 0x7F) b.append(c);
      else b.append('?');
    }
    return b.toString();
  }

  /** EXIF orientations for 0, 90, 180 and 270 degrees clockwise. */
  static final int[] CLOCKWISE = {1, 6, 3, 8};

  static int rotationIndex(int exifOrientation) {
    if (exifOrientation == 0) return 0;
    for (int i = 0; i < 4; i++) if (CLOCKWISE[i] == exifOrientation) return i;
    return -1;
  }

  static Matrix orientationMatrix(int o) {
    Matrix m = new Matrix();
    switch (o) {
      case 2:
        m.setScale(-1, 1);
        break;
      case 3:
        m.setRotate(180);
        break;
      case 4:
        m.setScale(1, -1);
        break;
      case 5:
        m.setRotate(90);
        m.postScale(-1, 1);
        break;
      case 6:
        m.setRotate(90);
        break;
      case 7:
        m.setRotate(-90);
        m.postScale(-1, 1);
        break;
      case 8:
        m.setRotate(-90);
        break;
      default:
        break;
    }
    return m;
  }

  public List<Photo> list() {
    List<Photo> out = new ArrayList<>();
    File[] dirs = root.listFiles();
    if (dirs != null)
      for (File f : dirs) {
        if (f.isDirectory() && !f.getName().startsWith("."))
          try {
            out.add(read(f));
          } catch (Exception ignored) {
          }
      }
    out.sort((a, b) -> Long.compare(b.metadata.optLong("savedAt"), a.metadata.optLong("savedAt")));
    return out;
  }

  private Photo read(File dir) throws Exception {
    File receipt = new File(dir, "receipt.json"), image = new File(dir, "photo.jpg");
    if (!image.exists() || !receipt.exists()) throw new IOException("Incomplete photo");
    String text;
    try (InputStream in = new FileInputStream(receipt)) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
      text = bytes.toString(StandardCharsets.UTF_8.name());
    }
    File original = new File(dir, "full-capture.jpg");
    File dng = new File(dir, "sensor.dng"), phone = new File(dir, "phone-isp.jpg");
    return new Photo(
        dir.getName(),
        image,
        original.exists() ? original : null,
        receipt,
        dng.isFile() ? dng : null,
        phone.isFile() ? phone : null,
        new JSONObject(text));
  }

  public Bitmap thumbnail(File file, int max) {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getPath(), o);
    o.inSampleSize = 1;
    while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > max * 2) o.inSampleSize *= 2;
    o.inJustDecodeBounds = false;
    Bitmap raw = BitmapFactory.decodeFile(file.getPath(), o);
    if (raw == null) return null;
    try {
      ExifInterface exif = new ExifInterface(file);
      Matrix m = orientationMatrix(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1));
      Bitmap oriented = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
      if (oriented != raw) {
        UltraHdrJpeg.preserveGainmapOnTransform(raw, oriented, m);
        raw.recycle();
      }
      return oriented;
    } catch (Exception ignored) {
      return raw;
    }
  }

  /** Rename-to-trash is reversible. Purge only when Undo expires / next deletion. */
  public boolean trash(Photo p) {
    return new File(root, p.id).renameTo(new File(root, ".trash-" + p.id));
  }

  public boolean restore(Photo p) {
    return new File(root, ".trash-" + p.id).renameTo(new File(root, p.id));
  }

  public void purgeTrash() {
    File[] dirs = root.listFiles();
    if (dirs != null) for (File f : dirs) if (f.getName().startsWith(".trash-")) deleteTree(f);
  }

  /** Converts a recipe display name (e.g. "Reggie's Portra") into a clean filename slug ("Reggies_Portra"). */
  public static String recipeFileSlug(String recipeName) {
    if (recipeName == null) return "";
    String stripped = recipeName.replace("'", "").replace("’", "").replace("`", "").trim();
    String slug = stripped.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
    return slug.length() > 40 ? slug.substring(0, 40).replaceAll("_+$", "") : slug;
  }

  /**
   * Builds a human-readable base filename that includes the film recipe and capture timestamp, e.g.
   * {@code Grain_Kodak_Portra_800_v3_20261108_143512_a1b2}.
   */
  public static String exportBaseName(String recipeName, long savedAtMs, String fallbackId) {
    String slug = recipeFileSlug(recipeName);
    long ts = savedAtMs;
    if (ts <= 0 && fallbackId != null) {
      int dash = fallbackId.indexOf('-');
      String numPart = dash > 0 ? fallbackId.substring(0, dash) : fallbackId;
      try {
        ts = Long.parseLong(numPart);
      } catch (NumberFormatException ignored) {
      }
    }
    String stamp;
    if (ts > 0) {
      java.text.SimpleDateFormat fmt =
          new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
      stamp = fmt.format(new Date(ts));
    } else {
      stamp = fallbackId == null || fallbackId.isEmpty() ? "0000" : fallbackId;
    }
    String tail = "";
    if (fallbackId != null) {
      int dash = fallbackId.lastIndexOf('-');
      if (dash >= 0 && dash + 1 < fallbackId.length()) {
        String sub = fallbackId.substring(dash + 1).replaceAll("[^A-Za-z0-9]", "");
        if (!sub.isEmpty()) tail = "_" + (sub.length() > 4 ? sub.substring(0, 4) : sub);
      }
    }
    if (slug.isEmpty()) {
      return "Grain_" + stamp + tail;
    }
    return "Grain_" + slug + "_" + stamp + tail;
  }

  public static String exportBaseName(Photo photo) {
    JSONObject r = photo.metadata == null ? null : photo.metadata.optJSONObject("recipe");
    String recipeName = r == null ? "" : r.optString("name", "");
    boolean hiRes = photo.metadata != null && photo.metadata.has("hiResMode");
    String label = hiRes ? (recipeName.isEmpty() ? "HiRes" : recipeName + "_HiRes") : recipeName;
    long savedAt = photo.metadata == null ? 0L : photo.metadata.optLong("savedAt", 0L);
    return exportBaseName(label, savedAt, photo.id);
  }

  public static String exportFileName(Photo photo, boolean full) {
    return exportBaseName(photo) + (full ? "_full" : "") + ".jpg";
  }

  public Uri export(Photo photo, boolean full) throws Exception {
    File source = full && photo.original != null ? photo.original : photo.image;
    String name = exportFileName(photo, full);
    ContentValues v = new ContentValues();
    v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
    v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Grain");
    v.put(MediaStore.Images.Media.IS_PENDING, 1);
    Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
    if (uri == null) throw new IOException("Cannot create gallery file");
    try {
      try (InputStream in = new FileInputStream(source);
          OutputStream out = context.getContentResolver().openOutputStream(uri)) {
        if (out == null) throw new IOException("No gallery stream");
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
      }
      ContentValues ready = new ContentValues();
      ready.put(MediaStore.Images.Media.IS_PENDING, 0);
      context.getContentResolver().update(uri, ready, null, null);
      return uri;
    } catch (Exception error) {
      context.getContentResolver().delete(uri, null, null);
      throw error;
    }
  }

  /**
   * Exports the RAW test set: DNG and phone JPEG to Pictures/Grain, the receipt to Download/Grain.
   * Returns a short list of what was written.
   */
  public List<String> exportTestFiles(Photo photo) throws Exception {
    List<String> done = new ArrayList<>();
    String base = exportBaseName(photo);
    if (photo.dng != null) {
      insert(photo.dng, base + "_sensor.dng", "image/x-adobe-dng", false);
      done.add("DNG");
    }
    if (photo.phoneJpeg != null) {
      insert(photo.phoneJpeg, base + "_phone.jpg", "image/jpeg", false);
      done.add("phone JPEG");
    }
    insert(photo.image, base + ".jpg", "image/jpeg", false);
    done.add("Grain JPEG");
    insert(photo.receipt, base + "_receipt.json", "application/json", true);
    done.add("receipt");
    return done;
  }

  /**
   * Writes a text file into Download/Grain. The camera report is longer than some clipboards will
   * take in one piece, so it has to be possible to save it as a file as well as copy it.
   */
  public Uri writeText(String name, String body) throws Exception {
    ContentValues v = new ContentValues();
    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
    v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Grain");
    v.put(MediaStore.Downloads.IS_PENDING, 1);
    Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
    if (uri == null) throw new IOException("Cannot create " + name);
    try {
      try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
        if (out == null) throw new IOException("No output stream for " + name);
        out.write(body.getBytes(StandardCharsets.UTF_8));
      }
      ContentValues ready = new ContentValues();
      ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
      context.getContentResolver().update(uri, ready, null, null);
      return uri;
    } catch (Exception error) {
      context.getContentResolver().delete(uri, null, null);
      throw error;
    }
  }

  private Uri insert(File source, String name, String mime, boolean download) throws Exception {
    ContentValues v = new ContentValues();
    Uri collection;
    if (download) {
      v.put(MediaStore.Downloads.DISPLAY_NAME, name);
      v.put(MediaStore.Downloads.MIME_TYPE, mime);
      v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Grain");
      v.put(MediaStore.Downloads.IS_PENDING, 1);
      collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
    } else {
      v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
      v.put(MediaStore.Images.Media.MIME_TYPE, mime);
      v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Grain");
      v.put(MediaStore.Images.Media.IS_PENDING, 1);
      collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }
    Uri uri = context.getContentResolver().insert(collection, v);
    if (uri == null) throw new IOException("Cannot create " + name);
    try {
      try (InputStream in = new FileInputStream(source);
          OutputStream out = context.getContentResolver().openOutputStream(uri)) {
        if (out == null) throw new IOException("No output stream for " + name);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
      }
      ContentValues ready = new ContentValues();
      ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
      context.getContentResolver().update(uri, ready, null, null);
      return uri;
    } catch (Exception error) {
      context.getContentResolver().delete(uri, null, null);
      throw error;
    }
  }

  private static void copy(File a, File b) throws IOException {
    try (InputStream in = new FileInputStream(a);
        FileOutputStream out = new FileOutputStream(b)) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      out.getFD().sync();
    }
  }

  private static void deleteTree(File f) {
    if (f.isDirectory()) {
      File[] files = f.listFiles();
      if (files != null) for (File child : files) deleteTree(child);
    }
    if (!f.delete() && f.exists()) android.util.Log.w("Grain", "Could not clean " + f);
  }
}
