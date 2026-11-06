package dev.grain.photo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppUpdaterTest {

  @Test
  public void semanticVersionComparisonHandlesTagsAndPrereleases() {
    assertTrue(AppUpdater.compareVersions("v0.6.0", "0.5.3") > 0);
    assertEquals(0, AppUpdater.compareVersions("v0.6.0", "0.6.0"));
    assertTrue(AppUpdater.compareVersions("0.5.3", "0.6.0") < 0);
    assertTrue(AppUpdater.compareVersions("0.6.0", "0.6.0-beta.1") > 0);
    assertTrue(AppUpdater.compareVersions("0.6.10", "0.6.2") > 0);
  }

  @Test
  public void parsesLatestPrereleaseSkippingDrafts() throws Exception {
    JSONArray arr = new JSONArray();
    arr.put(
        new JSONObject()
            .put("tag_name", "v0.7.0")
            .put("draft", true)
            .put(
                "assets",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("name", "Grain-v0.7.0.apk")
                            .put("browser_download_url", "https://example.com/0.7.0.apk"))));
    arr.put(
        new JSONObject()
            .put("tag_name", "v0.6.0")
            .put("name", "Grain v0.6.0 — Device-test beta")
            .put("draft", false)
            .put("prerelease", true)
            .put("body", "Magnifier, AF-C face tracking, focus bracketing, and in-app updates.")
            .put(
                "assets",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("name", "Grain-v0.6.0.apk")
                            .put("size", 5500000L)
                            .put(
                                "digest",
                                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            .put("browser_download_url", "https://example.com/Grain-v0.6.0.apk"))
                    .put(
                        new JSONObject()
                            .put("name", "SHA256SUMS.txt")
                            .put("browser_download_url", "https://example.com/SHA256SUMS.txt"))));

    AppUpdater.Release rel = AppUpdater.parseLatestRelease(arr.toString(), "0.5.3");
    assertNotNull(rel);
    assertEquals("v0.6.0", rel.tagName);
    assertEquals("Grain-v0.6.0.apk", rel.apkName);
    assertEquals(
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", rel.expectedSha256);

    assertNull(AppUpdater.parseLatestRelease(arr.toString(), "0.6.0"));
    assertNull(AppUpdater.parseLatestRelease(arr.toString(), "0.6.1"));
  }

  @Test
  public void endToEndCheckRedirectDownloadAndSha256Verification() throws Exception {
    byte[] fakeApk = new byte[128 * 1024];
    for (int i = 0; i < fakeApk.length; i++) fakeApk[i] = (byte) ((i * 31 + 7) & 0xff);
    String actualSha = sha256Hex(fakeApk);

    AtomicReference<String> storageAuthHeader = new AtomicReference<>();
    try (MiniHttp storage =
            new MiniHttp(
                (path, headers, out) -> {
                  storageAuthHeader.set(headers.get("authorization"));
                  writeResponse(out, "200 OK", "application/octet-stream", null, fakeApk);
                });
        MiniHttp api =
            new MiniHttp(
                new HandlerHolder()
                    .build(storage.port, fakeApk.length, actualSha))) {
      String releasesUrl = "http://127.0.0.1:" + api.port + "/releases";
      AppUpdater.Release rel = AppUpdater.checkLatest("0.5.3", releasesUrl, "ghp_test");
      assertNotNull(rel);
      assertEquals("v0.6.0", rel.tagName);
      assertNull(AppUpdater.checkLatest("0.6.0", releasesUrl, null));

      File outDir = Files.createTempDirectory("grain-upd").toFile();
      File dest = new File(outDir, rel.apkName);
      AtomicLong lastDone = new AtomicLong();
      File downloaded =
          AppUpdater.downloadApk(
              rel,
              dest,
              "ghp_test",
              (done, total) -> lastDone.set(done),
              new AtomicBoolean(false));
      assertTrue(downloaded.exists());
      assertEquals(fakeApk.length, lastDone.get());
      assertArrayEquals(fakeApk, Files.readAllBytes(downloaded.toPath()));
      // Cross-host redirect (127.0.0.1 -> localhost) must strip Authorization header!
      assertNull(storageAuthHeader.get());

      // Verify tampered checksum is rejected and cleaned up!
      AppUpdater.Release bad =
          new AppUpdater.Release(
              "v0.6.0",
              "0.6.0",
              "Grain v0.6.0",
              "",
              rel.apkName,
              rel.apkUrl,
              null,
              "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
              fakeApk.length,
              "");
      File badDest = new File(outDir, "tampered.apk");
      try {
        AppUpdater.downloadApk(bad, badDest, null, null, new AtomicBoolean(false));
        fail("Expected checksum mismatch IOException");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("sha-256"));
        assertFalse(badDest.exists());
      }
    }
  }

  private interface HttpHandler {
    void handle(String path, Map<String, String> headers, OutputStream out) throws Exception;
  }

  private static final class HandlerHolder {
    int apiPort;

    HttpHandler build(int storagePort, int apkLen, String sha) {
      return (path, headers, out) -> {
        String baseApi = "http://127.0.0.1:" + apiPort;
        if ("/releases".equals(path)) {
          JSONArray arr =
              new JSONArray()
                  .put(
                      new JSONObject()
                          .put("tag_name", "v0.6.0")
                          .put("name", "Grain v0.6.0")
                          .put("prerelease", true)
                          .put("body", "New camera release")
                          .put(
                              "assets",
                              new JSONArray()
                                  .put(
                                      new JSONObject()
                                          .put("name", "Grain-v0.6.0.apk")
                                          .put("size", apkLen)
                                          .put("browser_download_url", baseApi + "/download/apk"))
                                  .put(
                                      new JSONObject()
                                          .put("name", "SHA256SUMS.txt")
                                          .put("browser_download_url", baseApi + "/sums"))));
          writeResponse(
              out,
              "200 OK",
              "application/json",
              null,
              arr.toString().getBytes(StandardCharsets.UTF_8));
        } else if ("/download/apk".equals(path)) {
          writeResponse(
              out,
              "302 Found",
              "text/plain",
              "http://localhost:" + storagePort + "/blob/Grain-v0.6.0.apk",
              new byte[0]);
        } else if ("/sums".equals(path)) {
          byte[] sums = (sha + "  Grain-v0.6.0.apk\n").getBytes(StandardCharsets.UTF_8);
          writeResponse(out, "200 OK", "text/plain", null, sums);
        } else {
          writeResponse(out, "404 Not Found", "text/plain", null, new byte[0]);
        }
      };
    }
  }

  private static final class MiniHttp implements AutoCloseable {
    final ServerSocket server;
    final int port;
    final Thread thread;

    MiniHttp(HttpHandler handler) throws Exception {
      server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
      port = server.getLocalPort();
      // Inject port if built via HandlerHolder
      for (java.lang.reflect.Field f : handler.getClass().getDeclaredFields()) {
        f.setAccessible(true);
        Object v = f.get(handler);
        if (v instanceof HandlerHolder) ((HandlerHolder) v).apiPort = port;
      }
      thread =
          new Thread(
              () -> {
                while (!server.isClosed()) {
                  try (Socket s = server.accept()) {
                    BufferedReader r =
                        new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    String req = r.readLine();
                    if (req == null) continue;
                    String[] parts = req.split(" ");
                    String path = parts.length > 1 ? parts[1] : "/";
                    Map<String, String> headers = new HashMap<>();
                    String line;
                    while ((line = r.readLine()) != null && !line.isEmpty()) {
                      int idx = line.indexOf(':');
                      if (idx > 0) {
                        headers.put(
                            line.substring(0, idx).trim().toLowerCase(Locale.ROOT),
                            line.substring(idx + 1).trim());
                      }
                    }
                    handler.handle(path, headers, s.getOutputStream());
                  } catch (Exception ignored) {
                  }
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    @Override
    public void close() throws Exception {
      server.close();
    }
  }

  private static void writeResponse(
      OutputStream out, String status, String contentType, String location, byte[] body)
      throws IOException {
    StringBuilder h = new StringBuilder();
    h.append("HTTP/1.1 ").append(status).append("\r\n");
    h.append("Content-Type: ").append(contentType).append("\r\n");
    h.append("Content-Length: ").append(body.length).append("\r\n");
    h.append("Connection: close\r\n");
    if (location != null) h.append("Location: ").append(location).append("\r\n");
    h.append("\r\n");
    out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
    if (body.length > 0) out.write(body);
    out.flush();
  }

  private static String sha256Hex(byte[] bytes) throws Exception {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder sb = new StringBuilder(64);
    for (byte b : d) sb.append(String.format("%02x", b & 0xff));
    return sb.toString();
  }
}
