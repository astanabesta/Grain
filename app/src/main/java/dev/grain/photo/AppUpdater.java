package dev.grain.photo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Checks GitHub Releases for newer Grain builds, verifies the APK's SHA-256 digest, and streams it
 * directly into Android's {@link PackageInstaller} so updates happen inside Grain without opening a
 * browser or file manager.
 */
public final class AppUpdater {
  public static final String DEFAULT_RELEASES_URL =
      "https://api.github.com/repos/astanabesta/Grain/releases?per_page=10";
  public static final String DEFAULT_WEB_RELEASES_URL =
      "https://github.com/astanabesta/Grain/releases";

  public interface StatusListener {
    void onInstallStatus(int status, String message);
  }

  public interface ConfirmationLauncher {
    boolean launchConfirmIntent(Intent confirmIntent);
  }

  public interface ProgressListener {
    void onProgress(long downloadedBytes, long totalBytes);
  }

  private static volatile StatusListener statusListener;
  private static volatile ConfirmationLauncher confirmationLauncher;

  public static void setStatusListener(StatusListener listener) {
    statusListener = listener;
  }

  public static void setConfirmationLauncher(ConfirmationLauncher launcher) {
    confirmationLauncher = launcher;
  }

  static boolean tryLaunchFromForeground(Intent confirmIntent) {
    ConfirmationLauncher l = confirmationLauncher;
    return l != null && l.launchConfirmIntent(confirmIntent);
  }

  static void dispatchInstallStatus(int status, String message) {
    StatusListener l = statusListener;
    if (l != null) l.onInstallStatus(status, message);
  }

  /** Metadata for an installable Grain release published by GitHub Actions. */
  public static final class Release {
    public final String tagName;
    public final String version;
    public final String title;
    public final String notes;
    public final String apkName;
    public final String apkUrl;
    public final String sha256Url;
    public final String expectedSha256;
    public final long sizeBytes;
    public final String htmlUrl;

    public Release(
        String tagName,
        String version,
        String title,
        String notes,
        String apkName,
        String apkUrl,
        String sha256Url,
        String expectedSha256,
        long sizeBytes,
        String htmlUrl) {
      this.tagName = tagName;
      this.version = version;
      this.title = title;
      this.notes = notes;
      this.apkName = apkName;
      this.apkUrl = apkUrl;
      this.sha256Url = sha256Url;
      this.expectedSha256 = expectedSha256;
      this.sizeBytes = sizeBytes;
      this.htmlUrl = htmlUrl;
    }
  }

  private AppUpdater() {}

  /** Normalizes a tag like "v0.6.0" or "0.6.0-beta1" for comparison. */
  public static String cleanVersion(String raw) {
    if (raw == null) return "0.0.0";
    String s = raw.trim();
    if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1).trim();
    return s;
  }

  /**
   * Compares two semantic version strings (e.g. "v0.6.0" vs "0.5.3"). Returns positive if {@code a}
   * is newer than {@code b}, negative if older, 0 if equal.
   */
  public static int compareVersions(String a, String b) {
    String ca = cleanVersion(a), cb = cleanVersion(b);
    int dashA = ca.indexOf('-'), dashB = cb.indexOf('-');
    String coreA = dashA >= 0 ? ca.substring(0, dashA) : ca;
    String coreB = dashB >= 0 ? cb.substring(0, dashB) : cb;
    String[] pa = coreA.split("\\.");
    String[] pb = coreB.split("\\.");
    int n = Math.max(pa.length, pb.length);
    for (int i = 0; i < n; i++) {
      int va = i < pa.length ? parsePart(pa[i]) : 0;
      int vb = i < pb.length ? parsePart(pb[i]) : 0;
      if (va != vb) return Integer.compare(va, vb);
    }
    if (dashA < 0 && dashB >= 0) return 1;
    if (dashA >= 0 && dashB < 0) return -1;
    if (dashA >= 0 && dashB >= 0) return ca.substring(dashA + 1).compareTo(cb.substring(dashB + 1));
    return 0;
  }

  private static int parsePart(String part) {
    int end = 0;
    while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
    if (end == 0) return 0;
    try {
      return Integer.parseInt(part.substring(0, end));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Parses the GitHub Releases JSON array (including pre-releases) and returns the newest release
   * strictly newer than {@code currentVersion} that contains an APK asset, or null if up to date.
   */
  public static Release parseLatestRelease(String jsonText, String currentVersion) throws Exception {
    if (jsonText == null || jsonText.trim().isEmpty()) return null;
    String trimmed = jsonText.trim();
    JSONArray list =
        trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONArray().put(new JSONObject(trimmed));
    Release best = null;
    for (int i = 0; i < list.length(); i++) {
      JSONObject rel = list.optJSONObject(i);
      if (rel == null || rel.optBoolean("draft", false)) continue;
      String tag = rel.optString("tag_name", "").trim();
      if (tag.isEmpty()) continue;
      if (compareVersions(tag, currentVersion) <= 0) continue;
      if (best != null && compareVersions(tag, best.tagName) <= 0) continue;
      JSONArray assets = rel.optJSONArray("assets");
      if (assets == null || assets.length() == 0) continue;
      JSONObject apkAsset = null;
      String shaUrl = null;
      for (int j = 0; j < assets.length(); j++) {
        JSONObject a = assets.optJSONObject(j);
        if (a == null) continue;
        String name = a.optString("name", "");
        if ("SHA256SUMS.txt".equalsIgnoreCase(name)) {
          shaUrl = assetDownloadUrl(a, false);
        } else if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
          if (apkAsset == null || name.startsWith("Grain-")) apkAsset = a;
        }
      }
      if (apkAsset == null) continue;
      String apkUrl = assetDownloadUrl(apkAsset, false);
      if (apkUrl.isEmpty()) continue;
      String digest = apkAsset.optString("digest", "");
      String sha = null;
      if (digest.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
        String hex = digest.substring(7).trim().toLowerCase(Locale.ROOT);
        if (hex.length() == 64) sha = hex;
      }
      String title = rel.optString("name", tag);
      String notes = summarizeNotes(rel.optString("body", ""));
      best =
          new Release(
              tag,
              cleanVersion(tag),
              title.isEmpty() ? "Grain " + tag : title,
              notes,
              apkAsset.optString("name", "Grain-" + tag + ".apk"),
              apkUrl,
              shaUrl,
              sha,
              apkAsset.optLong("size", -1L),
              rel.optString("html_url", ""));
    }
    return best;
  }

  private static String assetDownloadUrl(JSONObject asset, boolean apiStream) {
    if (apiStream) {
      String api = asset.optString("url", "");
      if (!api.isEmpty()) return api;
    }
    return asset.optString("browser_download_url", asset.optString("url", ""));
  }

  /** Cleans markdown headings/install boilerplate into readable release notes for the dialog. */
  public static String summarizeNotes(String markdown) {
    if (markdown == null || markdown.trim().isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    for (String rawLine : markdown.split("\r?\n")) {
      String line = rawLine.trim();
      if (line.isEmpty()) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        continue;
      }
      if (line.startsWith("## Grain Photo ")) continue;
      if (line.startsWith("Install **Grain-") || line.startsWith("- **From v0.")) continue;
      if (line.startsWith("### ")) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        out.append(line.substring(4).trim().toUpperCase(Locale.ROOT)).append('\n');
        continue;
      }
      String clean = line.replace("**", "").replace("`", "");
      out.append(clean).append('\n');
      if (out.length() > 1400) break;
    }
    return out.toString().trim();
  }

  /** Extracts the 64-char hex SHA-256 for {@code apkName} from SHA256SUMS.txt content. */
  public static String parseSha256Sums(String sumsText, String apkName) {
    if (sumsText == null) return null;
    String fallback = null;
    for (String rawLine : sumsText.split("\r?\n")) {
      String line = rawLine.trim();
      if (line.length() < 64) continue;
      String[] parts = line.split("\\s+", 2);
      String hash = parts[0].toLowerCase(Locale.ROOT);
      if (!hash.matches("[0-9a-f]{64}")) continue;
      if (parts.length == 2) {
        String file = parts[1].startsWith("*") ? parts[1].substring(1).trim() : parts[1].trim();
        if (apkName != null && file.equals(apkName)) return hash;
      }
      if (fallback == null) fallback = hash;
    }
    return fallback;
  }

  /**
   * Checks GitHub Releases synchronously (call from a background executor).
   *
   * <p>When no personal API token is configured and the default Grain repository URL is used, this
   * resolves {@code github.com/.../releases/latest} + {@code SHA256SUMS.txt} directly instead of
   * {@code api.github.com}, completely avoiding GitHub's 60 req/hr unauthenticated API rate limit
   * on shared carrier/ISP CGNAT IPs (HTTP 403). If {@code api.github.com} is used and returns an
   * error (such as HTTP 403/429), it also falls back automatically to the web redirect path.
   */
  public static Release checkLatest(String currentVersion, String releasesUrl, String token)
      throws Exception {
    boolean defaultRepo = releasesUrl == null || releasesUrl.trim().isEmpty();
    boolean hasToken = token != null && !token.trim().isEmpty();
    if (defaultRepo && !hasToken) {
      try {
        return checkLatestViaWeb(currentVersion, DEFAULT_WEB_RELEASES_URL);
      } catch (Exception webErr) {
        try {
          String json = fetchText(DEFAULT_RELEASES_URL, null, "application/vnd.github+json");
          return parseLatestRelease(json, currentVersion);
        } catch (Exception ignored) {
          throw webErr;
        }
      }
    }
    String url = defaultRepo ? DEFAULT_RELEASES_URL : releasesUrl.trim();
    try {
      String json = fetchText(url, token, "application/vnd.github+json");
      return parseLatestRelease(json, currentVersion);
    } catch (IOException apiErr) {
      String webBase = defaultRepo ? DEFAULT_WEB_RELEASES_URL : deriveWebReleasesBase(url);
      if (webBase != null) {
        try {
          return checkLatestViaWeb(currentVersion, webBase);
        } catch (Exception ignored) {
          // Fall through and throw the original API error.
        }
      }
      throw apiErr;
    }
  }

  /**
   * Resolves the newest release via {@code <webReleasesBase>/latest} HTTP 302 redirect + {@code
   * SHA256SUMS.txt} + optional {@code releases.atom} notes without touching {@code api.github.com}.
   */
  public static Release checkLatestViaWeb(String currentVersion, String webReleasesBase)
      throws IOException {
    String base = webReleasesBase.endsWith("/")
        ? webReleasesBase.substring(0, webReleasesBase.length() - 1)
        : webReleasesBase;
    String tag = resolveLatestTagRedirect(base + "/latest");
    if (tag == null || tag.isEmpty()) return null;
    if (compareVersions(tag, currentVersion) <= 0) return null;

    String apkName = "Grain-" + tag + ".apk";
    String apkUrl = base + "/download/" + tag + "/" + apkName;
    String shaUrl = base + "/download/" + tag + "/SHA256SUMS.txt";
    String htmlUrl = base + "/tag/" + tag;

    String expectedSha;
    try {
      String sums = fetchText(shaUrl, null, "text/plain");
      expectedSha = parseSha256Sums(sums, apkName);
    } catch (IOException e) {
      // Release tag exists, or assets are still uploading; wait until SHA256SUMS.txt is published.
      if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) return null;
      throw e;
    }

    String title = "Grain " + tag;
    String notes = "";
    try {
      String atom = fetchText(base + ".atom", null, "application/atom+xml");
      String[] parsed = parseAtomEntry(atom, tag);
      if (parsed[0] != null && !parsed[0].isEmpty()) title = parsed[0];
      if (parsed[1] != null && !parsed[1].isEmpty()) notes = parsed[1];
    } catch (Exception ignored) {
      // Notes are cosmetic; tag + SHA-256 + APK URL are sufficient to install safely.
    }

    return new Release(
        tag,
        cleanVersion(tag),
        title,
        notes,
        apkName,
        apkUrl,
        shaUrl,
        expectedSha,
        -1L,
        htmlUrl);
  }

  static String deriveWebReleasesBase(String apiUrl) {
    if (apiUrl == null) return null;
    String marker = "://api.github.com/repos/";
    int idx = apiUrl.indexOf(marker);
    if (idx < 0) return null;
    String scheme = apiUrl.substring(0, idx);
    String rest = apiUrl.substring(idx + marker.length());
    String[] segs = rest.split("/");
    if (segs.length < 2 || segs[0].isEmpty() || segs[1].isEmpty()) return null;
    return scheme + "://github.com/" + segs[0] + "/" + segs[1] + "/releases";
  }

  static String extractTagFromReleaseLocation(String location) {
    if (location == null || location.isEmpty()) return null;
    String marker = "/releases/tag/";
    int idx = location.indexOf(marker);
    if (idx < 0) return null;
    String tail = location.substring(idx + marker.length());
    int q = tail.indexOf('?');
    if (q >= 0) tail = tail.substring(0, q);
    int h = tail.indexOf('#');
    if (h >= 0) tail = tail.substring(0, h);
    int s = tail.indexOf('/');
    if (s >= 0) tail = tail.substring(0, s);
    tail = tail.trim();
    return tail.isEmpty() ? null : tail;
  }

  private static String resolveLatestTagRedirect(String latestUrl) throws IOException {
    URL current = new URL(latestUrl);
    for (int hop = 0; hop < 6; hop++) {
      HttpURLConnection conn = (HttpURLConnection) current.openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.setConnectTimeout(10_000);
      conn.setReadTimeout(15_000);
      conn.setRequestProperty("User-Agent", "Grain-Photo/" + BuildConfig.VERSION_NAME);
      conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
      try {
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_PERM
            || code == HttpURLConnection.HTTP_MOVED_TEMP
            || code == HttpURLConnection.HTTP_SEE_OTHER
            || code == 307
            || code == 308) {
          String loc = conn.getHeaderField("Location");
          if (loc == null || loc.isEmpty()) return null;
          String tag = extractTagFromReleaseLocation(loc);
          if (tag != null) return tag;
          current = new URL(current, loc);
          continue;
        }
        if (code == HttpURLConnection.HTTP_NOT_FOUND) return null;
        if (code >= 200 && code < 300) {
          return extractTagFromReleaseLocation(current.toString());
        }
        throw new IOException("Update server returned HTTP " + code);
      } finally {
        conn.disconnect();
      }
    }
    throw new IOException("Too many redirects");
  }

  /** Extracts {@code [title, plainTextSummary]} for {@code tag} from GitHub's {@code releases.atom}. */
  static String[] parseAtomEntry(String atomXml, String tag) {
    String[] res = new String[] {"", ""};
    if (atomXml == null || tag == null) return res;
    String needle = "/releases/tag/" + tag + "\"";
    int pos = 0;
    while (true) {
      int entryStart = atomXml.indexOf("<entry>", pos);
      if (entryStart < 0) break;
      int entryEnd = atomXml.indexOf("</entry>", entryStart);
      if (entryEnd < 0) break;
      String entry = atomXml.substring(entryStart, entryEnd);
      pos = entryEnd + 8;
      if (!entry.contains(needle) && !entry.contains("/" + tag + "</id>")) continue;
      res[0] = decodeXmlEntities(betweenTags(entry, "title")).trim();
      String html = decodeXmlEntities(betweenTags(entry, "content"));
      if (!html.isEmpty()) {
        String text =
            html.replaceAll("(?i)<h3[^>]*>", "\n### ")
                .replaceAll("(?i)<h2[^>]*>", "## ")
                .replaceAll("(?i)</h[1-6]>", "\n")
                .replaceAll("(?i)<li[^>]*>", "- ")
                .replaceAll("(?i)</li>", "\n")
                .replaceAll("(?i)<p[^>]*>", "")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "");
        res[1] = summarizeNotes(decodeXmlEntities(text));
      }
      break;
    }
    return res;
  }

  private static String betweenTags(String xml, String tagName) {
    String open = "<" + tagName;
    int s = xml.indexOf(open);
    if (s < 0) return "";
    int gt = xml.indexOf('>', s + open.length());
    if (gt < 0) return "";
    int e = xml.indexOf("</" + tagName + ">", gt + 1);
    if (e < 0) return "";
    return xml.substring(gt + 1, e);
  }

  private static String decodeXmlEntities(String s) {
    if (s == null || s.isEmpty()) return "";
    return s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&");
  }

  /**
   * Downloads {@code release.apkUrl} to {@code destination}, checks SHA-256 integrity when
   * available, and writes atomically so partial downloads are never passed to the installer.
   */
  public static File downloadApk(
      Release release,
      File destination,
      String token,
      ProgressListener progress,
      AtomicBoolean cancelled)
      throws Exception {
    String expectedSha = release.expectedSha256;
    if ((expectedSha == null || expectedSha.isEmpty())
        && release.sha256Url != null
        && !release.sha256Url.isEmpty()) {
      try {
        String sums = fetchText(release.sha256Url, token, "application/octet-stream");
        expectedSha = parseSha256Sums(sums, release.apkName);
      } catch (Exception ignored) {
        // Proceed if SHA256SUMS.txt could not be fetched; digest is checked whenever present.
      }
    }
    File parent = destination.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("Could not create update cache folder");
    }
    File temp = new File(destination.getParentFile(), destination.getName() + ".part");
    if (temp.exists() && !temp.delete()) {
      throw new IOException("Could not clean previous partial update");
    }
    HttpURLConnection conn = openWithRedirects(release.apkUrl, token, "application/octet-stream");
    try {
      long total = conn.getContentLengthLong();
      if (total <= 0 && release.sizeBytes > 0) total = release.sizeBytes;
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[32 * 1024];
      long readTotal = 0;
      try (InputStream in = new BufferedInputStream(conn.getInputStream());
          OutputStream out = new FileOutputStream(temp)) {
        int n;
        while ((n = in.read(buf)) >= 0) {
          if (cancelled != null && cancelled.get()) {
            throw new IOException("Update cancelled");
          }
          out.write(buf, 0, n);
          sha256.update(buf, 0, n);
          readTotal += n;
          if (progress != null) progress.onProgress(readTotal, total);
        }
        out.flush();
      }
      if (readTotal < 1024) {
        throw new IOException("Downloaded APK is incomplete (" + readTotal + " bytes)");
      }
      if (expectedSha != null && !expectedSha.isEmpty()) {
        String actualSha = hex(sha256.digest());
        if (!expectedSha.equalsIgnoreCase(actualSha)) {
          //noinspection ResultOfMethodCallIgnored
          temp.delete();
          throw new IOException("SHA-256 verification failed for " + release.apkName);
        }
      }
      if (destination.exists() && !destination.delete()) {
        //noinspection ResultOfMethodCallIgnored
        temp.delete();
        throw new IOException("Could not replace cached APK");
      }
      if (!temp.renameTo(destination)) {
        throw new IOException("Could not finalize downloaded APK");
      }
      return destination;
    } catch (Exception e) {
      //noinspection ResultOfMethodCallIgnored
      temp.delete();
      throw e;
    } finally {
      conn.disconnect();
    }
  }

  /**
   * Returns true on Xiaomi / Redmi / POCO / BlackShark devices (MIUI & HyperOS), where Xiaomi's
   * {@code PKMSImpl.assertCallerAndPackage} rejects {@code USER_ACTION_NOT_REQUIRED} sessions with
   * {@code STATUS_FAILURE_ABORTED: Permission denied} and HyperOS may suppress background receiver
   * confirmation intents.
   */
  public static boolean isXiaomiBrand(String manufacturer, String brand) {
    String m = manufacturer == null ? "" : manufacturer.toLowerCase(Locale.ROOT);
    String b = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
    return m.contains("xiaomi")
        || m.contains("redmi")
        || m.contains("poco")
        || m.contains("blackshark")
        || b.contains("xiaomi")
        || b.contains("redmi")
        || b.contains("poco")
        || b.contains("blackshark");
  }

  public static boolean isXiaomiDevice() {
    return isXiaomiBrand(Build.MANUFACTURER, Build.BRAND);
  }

  /**
   * Distinguishes OEM/system permission rejections that report {@code STATUS_FAILURE_ABORTED} (such
   * as HyperOS's {@code INSTALL_FAILED_ABORTED: Permission denied}) from a genuine user tap on
   * Cancel.
   */
  public static boolean isSystemBlockedAbort(int status, String message) {
    if (status != PackageInstaller.STATUS_FAILURE_ABORTED || message == null) return false;
    String m = message.toLowerCase(Locale.ROOT);
    return m.contains("permission")
        || m.contains("install_failed")
        || m.contains("blocked")
        || m.contains("restricted")
        || m.contains("not allowed");
  }

  /**
   * Opens Android's standard system package installer directly via {@link FileProvider} ({@code
   * content://} URI + {@code application/vnd.android.package-archive}). Works reliably from a
   * foreground {@link Activity} across Xiaomi HyperOS/MIUI, ColorOS, OneUI, and stock Android.
   */
  @SuppressLint("RequestInstallPackagesPolicy")
  public static void launchSystemApkInstaller(Context context, File apkFile) throws IOException {
    if (apkFile == null || !apkFile.isFile() || apkFile.length() <= 0) {
      throw new IOException("Update APK file is missing");
    }
    Uri uri =
        FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setDataAndType(uri, "application/vnd.android.package-archive");
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (!(context instanceof Activity)) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
    try {
      context.startActivity(intent);
    } catch (Exception e) {
      throw new IOException("Could not open system package installer: " + e.getMessage(), e);
    }
  }

  /**
   * Installs {@code apkFile}. On Xiaomi/Redmi/POCO (HyperOS/MIUI), launches the system package
   * installer directly via {@link FileProvider} to avoid HyperOS's {@code PKMSImpl} abort on silent
   * sessions. On other devices, streams into a {@link PackageInstaller.Session} and falls back to
   * {@link #launchSystemApkInstaller} if the session cannot be created or committed.
   */
  @SuppressLint({"RequestInstallPackagesPolicy", "MutableImplicitPendingIntent"})
  public static void installApk(Context context, File apkFile) throws IOException {
    if (apkFile == null || !apkFile.isFile() || apkFile.length() <= 0) {
      throw new IOException("Update APK file is missing");
    }
    if (isXiaomiDevice()) {
      launchSystemApkInstaller(context, apkFile);
      return;
    }
    PackageInstaller installer = context.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params =
        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(context.getPackageName());
    params.setSize(apkFile.length());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
    }
    int sessionId = -1;
    try {
      sessionId = installer.createSession(params);
      try (PackageInstaller.Session session = installer.openSession(sessionId)) {
        try (InputStream in = new FileInputStream(apkFile);
            OutputStream out = session.openWrite("grain-update.apk", 0, apkFile.length())) {
          byte[] buf = new byte[64 * 1024];
          int n;
          while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
          }
          session.fsync(out);
        }
        Intent callback = new Intent(context, UpdateReceiver.class);
        callback.setAction(UpdateReceiver.ACTION_INSTALL_STATUS);
        callback.setPackage(context.getPackageName());
        int flags =
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, callback, flags);
        session.commit(pending.getIntentSender());
      }
    } catch (IOException | RuntimeException e) {
      if (sessionId >= 0) {
        try {
          installer.abandonSession(sessionId);
        } catch (Exception ignored) {
        }
      }
      launchSystemApkInstaller(context, apkFile);
    }
  }

  private static String fetchText(String url, String token, String accept) throws IOException {
    HttpURLConnection conn = openWithRedirects(url, token, accept);
    try (InputStream in = new BufferedInputStream(conn.getInputStream());
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) >= 0) {
        out.write(buf, 0, n);
        if (out.size() > 2 * 1024 * 1024) throw new IOException("Release metadata too large");
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } finally {
      conn.disconnect();
    }
  }

  private static HttpURLConnection openWithRedirects(String rawUrl, String token, String accept)
      throws IOException {
    URL current = new URL(rawUrl);
    String initialHost = current.getHost();
    for (int hop = 0; hop < 6; hop++) {
      HttpURLConnection conn = (HttpURLConnection) current.openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.setConnectTimeout(10_000);
      conn.setReadTimeout(25_000);
      conn.setRequestProperty("User-Agent", "Grain-Photo/" + BuildConfig.VERSION_NAME);
      if (accept != null && !accept.isEmpty()) conn.setRequestProperty("Accept", accept);
      if (token != null
          && !token.trim().isEmpty()
          && current.getHost().equalsIgnoreCase(initialHost)) {
        conn.setRequestProperty("Authorization", "Bearer " + token.trim());
      }
      int code = conn.getResponseCode();
      if (code == HttpURLConnection.HTTP_MOVED_PERM
          || code == HttpURLConnection.HTTP_MOVED_TEMP
          || code == HttpURLConnection.HTTP_SEE_OTHER
          || code == 307
          || code == 308) {
        String loc = conn.getHeaderField("Location");
        conn.disconnect();
        if (loc == null || loc.isEmpty()) throw new IOException("Redirect without Location header");
        current = new URL(current, loc);
        continue;
      }
      if (code < 200 || code >= 300) {
        conn.disconnect();
        throw new IOException("Update server returned HTTP " + code);
      }
      return conn;
    }
    throw new IOException("Too many redirects");
  }

  static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16));
      sb.append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }
}
