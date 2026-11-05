package dev.grain.photo;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
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
    public final JSONObject metadata;

    Photo(String id, File image, File original, File receipt, JSONObject metadata) {
      this.id = id;
      this.image = image;
      this.original = original;
      this.receipt = receipt;
      this.metadata = metadata;
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
    String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    File staging = new File(root, "." + id);
    if (!staging.mkdir()) throw new IOException("Cannot start photo save");
    try {
      File source = new File(staging, "capture.jpg");
      try (FileOutputStream out = new FileOutputStream(source)) {
        out.write(jpeg);
        out.getFD().sync();
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
      } else {
        Bitmap decoded = BitmapFactory.decodeFile(source.getPath());
        if (decoded == null) throw new IOException("Cannot decode JPEG");
        Bitmap upright = null, cropped = null;
        try {
          Matrix m = orientationMatrix(orientation);
          upright =
              Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
          if (upright != decoded) {
            decoded.recycle();
            decoded = null;
          }
          cropped = Bitmap.createBitmap(upright, crop[0], crop[1], crop[2], crop[3]);
          try (FileOutputStream out = new FileOutputStream(finalImage)) {
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 97, out))
              throw new IOException("JPEG export failed");
            out.getFD().sync();
          }
          ExifInterface output = new ExifInterface(finalImage);
          String[] tags = {
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH
          };
          for (String tag : tags) {
            String value = exif.getAttribute(tag);
            if (value != null) output.setAttribute(tag, value);
          }
          output.setAttribute(ExifInterface.TAG_ORIENTATION, "1");
          output.setAttribute(ExifInterface.TAG_SOFTWARE, "Grain Photo 0.1.1");
          output.saveAttributes();
        } finally {
          if (cropped != null && !cropped.isRecycled()) cropped.recycle();
          if (upright != null && !upright.isRecycled()) upright.recycle();
          if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
      }
      metadata
          .put("id", id)
          .put("width", crop[2])
          .put("height", crop[3])
          .put("sourceWidth", uprightW)
          .put("sourceHeight", uprightH)
          .put("ratio", ratio)
          .put("digitalZoom", zoom)
          .put("fullCaptureKept", keepFull)
          .put(
              "originalMeaning", "Full unzoomed ISP-recipe JPEG, NOT unfiltered and NOT sensor RAW")
          .put("geometryOnlyPostProcessing", !full)
          .put("savedAt", System.currentTimeMillis());
      if (keepFull) {
        if (!source.renameTo(new File(staging, "full-capture.jpg")))
          throw new IOException("Cannot retain source");
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
    return new Photo(
        dir.getName(), image, original.exists() ? original : null, receipt, new JSONObject(text));
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
      if (oriented != raw) raw.recycle();
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

  public Uri export(Photo photo, boolean full) throws Exception {
    File source = full && photo.original != null ? photo.original : photo.image;
    String name = "Grain_" + photo.id + (full ? "_full" : "") + ".jpg";
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
