package dev.grain.photo;

import static org.junit.Assert.*;

import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * EXIF ASCII-type fields (Make/Model/ImageDescription, and the ASCII-prefixed UserComment) cannot
 * store non-ASCII characters — ExifInterface mangles them to '?'. {@link PhotoStore#exifAscii}
 * maps the typography used in recipe names (·, –, —, curly quotes) to clean ASCII so saved JPEGs
 * keep readable metadata, which this verifies end-to-end through a real JPEG file.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExifTextTest {

  // 16x12 warm-white JPEG (JFIF, no EXIF) used as the round-trip carrier.
  private static final String TINY_JPEG_B64 =
      "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYG"
      + "BwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoK"
      + "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAMABADASIAAhEBAxEB/8QA"
      + "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIh"
      + "MUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW"
      + "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG"
      + "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQF"
      + "BgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAV"
      + "YnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE"
      + "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq"
      + "8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD9sKKKK887D//Z";

  @Test
  public void exifAsciiMapsTypographyAndLeavesAsciiUntouched() {
    assertEquals("iPhone XS Max - Warm", PhotoStore.exifAscii("iPhone XS Max · Warm"));
    assertEquals("Grain Photo - iPhone XS Pro - Rich",
        PhotoStore.exifAscii("Grain Photo · iPhone XS Pro — Rich"));
    assertEquals("She said \"hi\" - it's fine",
        PhotoStore.exifAscii("She said “hi” – it’s fine"));
    assertEquals("Plain ASCII 123 (f/1.7)", PhotoStore.exifAscii("Plain ASCII 123 (f/1.7)"));
    assertEquals("?", PhotoStore.exifAscii("中"));
    assertNull(PhotoStore.exifAscii(null));
  }

  @Test
  public void typedExifStringsSurviveAJpegRoundTrip() throws Exception {
    File jpeg = Files.createTempFile("grain-exif-", ".jpg").toFile();
    try {
      try (FileOutputStream out = new FileOutputStream(jpeg)) {
        out.write(Base64.getDecoder().decode(TINY_JPEG_B64));
      }
      String description = "iPhone XS Max · Warm (A12 Smart HDR · XS Max Warm, Vibrant sRGB + Ultra HDR)";
      String comment = "Grain Photo · iPhone XS Max · Warm";

      ExifInterface out0 = new ExifInterface(jpeg);
      out0.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, PhotoStore.exifAscii(description));
      out0.setAttribute(ExifInterface.TAG_USER_COMMENT, PhotoStore.exifAscii(comment));
      out0.setAttribute(ExifInterface.TAG_MAKE, PhotoStore.exifAscii("Xiaomi"));
      out0.setAttribute(ExifInterface.TAG_MODEL, PhotoStore.exifAscii("23090RA98I"));
      out0.saveAttributes();

      ExifInterface back = new ExifInterface(jpeg);
      assertEquals(
          "iPhone XS Max - Warm (A12 Smart HDR - XS Max Warm, Vibrant sRGB + Ultra HDR)",
          back.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
      assertEquals(
          "Grain Photo - iPhone XS Max - Warm",
          back.getAttribute(ExifInterface.TAG_USER_COMMENT));
      assertEquals("Xiaomi", back.getAttribute(ExifInterface.TAG_MAKE));
      assertEquals("23090RA98I", back.getAttribute(ExifInterface.TAG_MODEL));
    } finally {
      //noinspection ResultOfMethodCallIgnored
      jpeg.delete();
    }
  }
}
