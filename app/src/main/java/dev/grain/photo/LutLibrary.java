package dev.grain.photo;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.FujiRecipe;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * Official LUTs imported by the user from the zips they downloaded: Fujifilm's F-Log2 film
 * simulations (fujifilm-x.com) and Sony's s709 (Sony's S-Log grading guide). Stored privately in
 * the app (filesDir/luts) as compact binary tables, Sony's exactly as published (its S-Log3 input
 * is recorded and converted per sample, see CubeLut.Input); never bundled, uploaded or exported by
 * Grain.
 */
final class LutLibrary {
  static final String DOWNLOAD_PAGE = "https://www.fujifilm-x.com/en-in/support/download/lut/";
  static final String RECOMMENDED_PACK =
      "https://dl.fujifilm-x.com/support/lut/gfx-eterna-55-3d-lut-v110.zip";
  /** F-Log2 (not F-Log2 C) 33-point LUTs, e.g. .../33Grid/F-Log2/FLog2_to_CLASSIC-CHROME_33grid_V.1.00.cube */
  static final Pattern ENTRY = CubeLut.FUJI_FLOG2_33;
  /** Sony's s709 (S-Log3 / S-Gamut3.Cine, or S-Gamut3), from s709Cubes.zip. */
  static final Pattern SONY_ENTRY = CubeLut.SONY_S709;

  static final String SONY_ZIP =
      "https://support.d-imaging.sony.co.jp/download/NEX/vcFhmg6zov/s709Cubes.zip?fm=en";
  static final String SONY_PAGE = FujiRecipe.SONY_S709_PAGE;

  static final class ImportResult {
    final List<String> imported = new ArrayList<>();
    String source = "";
  }

  private final File dir;
  private final Map<String, CubeLut> cache = new HashMap<>();

  LutLibrary(Context context) {
    dir = new File(context.getFilesDir(), "luts");
    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("LUT folder unavailable");
  }

  private static String fileKey(String sim) {
    return sim.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.+\\-]", "_");
  }

  private File file(String sim) {
    return new File(dir, fileKey(sim) + ".glut");
  }

  synchronized boolean has(String sim) {
    return file(sim).isFile();
  }

  synchronized CubeLut load(String sim) throws IOException {
    String key = fileKey(sim);
    CubeLut lut = cache.get(key);
    if (lut != null) return lut;
    File f = file(sim);
    if (!f.isFile()) throw new IOException(sim + " LUT not imported");
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
      lut = CubeLut.read(in);
    }
    if (FujiRecipe.sonyLut(sim)) lut = lut.withInput(sonyInput(sim));
    cache.clear(); // keep one table in memory (~430 KB)
    cache.put(key, lut);
    return lut;
  }

  /** The input a stored Sony table expects (recorded at import; S-Gamut3.Cine by default). */
  private CubeLut.Input sonyInput(String sim) {
    JSONObject sims = manifest().optJSONObject("sims");
    JSONObject entry = sims == null ? null : sims.optJSONObject(sim);
    String name = entry == null ? "" : entry.optString("input", "");
    try {
      CubeLut.Input in = CubeLut.Input.valueOf(name);
      if (in != CubeLut.Input.FLOG2_FGAMUT) return in;
    } catch (IllegalArgumentException ignored) {
      // not recorded: Grain imports S-Gamut3.Cine
    }
    return CubeLut.Input.SLOG3_SGAMUT3_CINE;
  }

  synchronized JSONObject manifest() {
    File f = new File(dir, "manifest.json");
    if (!f.isFile()) return new JSONObject();
    try (InputStream in = new FileInputStream(f)) {
      byte[] b = new byte[(int) Math.min(1 << 20, f.length())];
      int n = 0, r;
      while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
      return new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  synchronized List<String> available() {
    List<String> out = new ArrayList<>();
    JSONObject sims = manifest().optJSONObject("sims");
    if (sims != null)
      for (Iterator<String> it = sims.keys(); it.hasNext(); ) {
        String s = it.next();
        if (has(s)) out.add(s);
      }
    return out;
  }

  /** Imported Fujifilm film simulations, without the packs' technical Rec.709 conversions. */
  List<String> films() {
    List<String> out = new ArrayList<>();
    for (String s : available()) if (!FujiRecipe.isTechnical(s) && !FujiRecipe.sonyLut(s)) out.add(s);
    return out;
  }

  String summary() {
    List<String> all = available();
    if (all.isEmpty()) return "Not imported yet";
    boolean sony = has(FujiRecipe.SONY_S709_LUT);
    int films = films().size(), technical = 0;
    for (String s : all) if (FujiRecipe.isTechnical(s)) technical++;
    JSONObject m = manifest();
    String fuji =
        films == 0
            ? ""
            : films + " film simulation" + (films == 1 ? "" : "s")
                + (technical > 0 ? " + " + technical + " video LUT" + (technical == 1 ? "" : "s") : "")
                + " · " + m.optString("model", "Fujifilm");
    return fuji + (sony ? (fuji.isEmpty() ? "" : " · ") + "Sony s709" : "");
  }

  /**
   * Imports every F-Log2 33-grid LUT from a Fujifilm zip and Sony's s709 from s709Cubes.zip, or one
   * such .cube file on its own.
   */
  synchronized ImportResult importFrom(ContentResolver resolver, Uri uri, File cacheDir)
      throws IOException {
    ImportResult result = new ImportResult();
    result.source = displayName(resolver, uri);
    File tmp = new File(cacheDir, "lut-import.tmp");
    long size = 0;
    try (InputStream in = resolver.openInputStream(uri);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
      if (in == null) throw new IOException("Cannot open the selected file");
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) {
        size += n;
        if (size > 400L * 1024 * 1024) throw new IOException("File too large for a LUT pack");
        out.write(buf, 0, n);
      }
    }
    JSONObject manifest = manifest();
    JSONObject sims = manifest.optJSONObject("sims");
    if (sims == null) sims = new JSONObject();
    String model = "";
    try {
      byte[] magic = new byte[4];
      try (InputStream in = new FileInputStream(tmp)) {
        if (in.read(magic) != 4) throw new IOException("Empty file");
      }
      if (magic[0] == 'P' && magic[1] == 'K') {
        boolean sonyCine = false;
        try (ZipFile zip = new ZipFile(tmp)) {
          Enumeration<? extends ZipEntry> entries = zip.entries();
          while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.isDirectory() || e.getName().contains("__MACOSX")) continue;
            Matcher m = ENTRY.matcher(e.getName());
            if (!m.find()) {
              Matcher sony = SONY_ENTRY.matcher(e.getName());
              if (!sony.find()) continue;
              CubeLut.Input input = sonyInput(sony);
              // one s709 is enough: S-Gamut3.Cine (Sony's cinema gamut) wins over S-Gamut3
              if (sonyCine && input != CubeLut.Input.SLOG3_SGAMUT3_CINE) continue;
              try (InputStream in = zip.getInputStream(e)) {
                storeSony(sims, e.getName(), input, CubeLut.parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
              }
              sonyCine |= input == CubeLut.Input.SLOG3_SGAMUT3_CINE;
              if (!result.imported.contains(FujiRecipe.SONY_S709_LUT)) result.imported.add(FujiRecipe.SONY_S709_LUT);
              continue;
            }
            CubeLut lut;
            try (InputStream in = zip.getInputStream(e)) {
              lut = CubeLut.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            if (lut.size != 33) continue;
            String sim = m.group(1);
            store(sim, lut);
            sims.put(sim, new JSONObject().put("entry", e.getName()).put("title", lut.title)
                .put("model", lut.model).put("gamut", lut.gamut));
            if (model.isEmpty()) model = lut.model;
            result.imported.add(sim);
          }
        }
      } else if (SONY_ENTRY.matcher(result.source).find()) {
        Matcher sony = SONY_ENTRY.matcher(result.source);
        if (!sony.find()) throw new IOException("Not Sony's s709 file");
        try (InputStream in = new FileInputStream(tmp)) {
          storeSony(sims, result.source, sonyInput(sony),
              CubeLut.parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        result.imported.add(FujiRecipe.SONY_S709_LUT);
      } else {
        Matcher m = ENTRY.matcher(result.source);
        if (!m.find())
          throw new IOException(
              "Choose Fujifilm's LUT zip or Sony's s709Cubes.zip, or a .cube file from one of them");
        CubeLut lut;
        try (InputStream in = new FileInputStream(tmp)) {
          lut = CubeLut.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        if (lut.size != 33) throw new IOException("Expected a 33-point LUT");
        String sim = m.group(1);
        store(sim, lut);
        sims.put(sim, new JSONObject().put("entry", result.source).put("title", lut.title)
            .put("model", lut.model).put("gamut", lut.gamut));
        model = lut.model;
        result.imported.add(sim);
      }
      if (result.imported.isEmpty())
        throw new IOException(
            "No F-Log2 33-grid LUTs or Sony s709 found. Use Fujifilm's official pack (GFX ETERNA"
                + " 55 v1.10 includes Classic Chrome) or Sony's s709Cubes.zip.");
      manifest.put("sims", sims).put("source", result.source).put("importedAt", System.currentTimeMillis());
      if (!model.isEmpty()) manifest.put("model", model);
      File mf = new File(dir, "manifest.json.tmp");
      try (OutputStream out = new FileOutputStream(mf)) {
        out.write(manifest.toString(1).getBytes(StandardCharsets.UTF_8));
      }
      File target = new File(dir, "manifest.json");
      if (!mf.renameTo(target)) throw new IOException("Cannot save LUT list");
      cache.clear();
      return result;
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e.getMessage(), e);
    } finally {
      if (!tmp.delete() && tmp.exists()) android.util.Log.w("Grain", "LUT temp file kept");
    }
  }

  private static CubeLut.Input sonyInput(Matcher sony) {
    return sony.group(1).isEmpty() ? CubeLut.Input.SLOG3_SGAMUT3 : CubeLut.Input.SLOG3_SGAMUT3_CINE;
  }

  /** Stores Sony's s709 table exactly as published, with the input it expects. */
  private void storeSony(JSONObject sims, String entry, CubeLut.Input input, CubeLut lut) throws Exception {
    if (lut.size < 17) throw new IOException("Sony s709 LUT too coarse: " + lut.size + " points");
    CubeLut described =
        lut.describedAs("Sony s709 (" + input.label + " to Rec.709)", "Sony", input.label);
    store(FujiRecipe.SONY_S709_LUT, described);
    sims.put(FujiRecipe.SONY_S709_LUT, new JSONObject().put("entry", entry).put("title", described.title)
        .put("model", "Sony").put("gamut", input.label).put("input", input.name())
        .put("points", lut.size));
  }

  private void store(String sim, CubeLut lut) throws IOException {
    File tmp = new File(dir, fileKey(sim) + ".tmp");
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
      lut.write(out);
    }
    if (!tmp.renameTo(file(sim))) throw new IOException("Cannot store " + sim + " LUT");
  }

  synchronized void clear() {
    File[] files = dir.listFiles();
    if (files != null) for (File f : files) if (!f.delete()) android.util.Log.w("Grain", "Kept " + f);
    cache.clear();
  }

  private static String displayName(ContentResolver resolver, Uri uri) {
    try (Cursor c = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        String name = c.getString(0);
        if (name != null) return name;
      }
    } catch (Exception ignored) {
    }
    String last = uri.getLastPathSegment();
    return last == null ? "LUT" : last;
  }
}
