package dev.grain.photo;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Live viewfinder renderer: the camera writes into an external OES texture, and we draw it through
 * a tiny fullscreen program that optionally passes every pixel through a 3D "look" LUT — the exact
 * same colour transform (tone curve + film LUT + sensor calibration, or the Apple path) the RAW
 * development pipeline applies, baked once per recipe into a 17³ LUT by {@link LiveLook}. A plain
 * sRGB→sRGB map, cost on the GPU: one texture fetch per pixel — i.e. nothing next to the camera
 * pipeline itself.
 *
 * <p>Also serves the app's preview-read-back needs (histogram, focus peaking, magnifier): {@link
 * #readPixels} re-renders the frame into a small off-screen FBO on the GL thread and hands back a
 * Bitmap, so what you analyse is exactly what's on screen (LUT included).
 */
final class LivePreviewRenderer implements GLSurfaceView.Renderer {

  interface Callback {
    /** The camera-facing SurfaceTexture appeared (or was recreated) — open the camera on it. */
    void surfaceReady(SurfaceTexture texture);

    /** The GL surface is gone (pause/destroy) — treat the preview as unavailable. */
    void surfaceGone();

    /** View size changed. */
    void sizeChanged(int w, int h);

    /**
     * Anything on GLThread went wrong (shader compile, driver quirk, context loss). The host must
     * switch to the TextureView preview: an exception escaping GLThread kills the whole process.
     */
    void glFailed(Throwable error);
  }

  private static final String TAG = "GrainPreview";

  private static final String VERTEX =
      "#version 300 es\n"
          + "layout(location=0) in vec2 aPos;\n"
          + "layout(location=1) in vec2 aUV;\n"
          + "uniform mat4 uTexMatrix;\n"
          + "out vec2 vUV;\n"
          + "void main(){ vUV = (uTexMatrix * vec4(aUV, 0.0, 1.0)).xy; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

  // Package-private so a JVM test can guard against the missing-precision regression.
  static final String FRAGMENT =
      "#version 300 es\n"
          + "#extension GL_OES_EGL_image_external_essl3 : require\n"
          + "precision highp float;\n"
          // GLSL ES 3.0 defines no default precision for sampler3D in the fragment language:
          // an unqualified uniform fails to compile on strict drivers. Never drop these qualifiers.
          + "uniform highp samplerExternalOES uTex;\n"
          + "uniform highp sampler3D uLut;\n"
          + "uniform float uLutSize;\n" // < .5 → straight passthrough
          + "in vec2 vUV;\n"
          + "out vec4 fragColor;\n"
          + "void main(){\n"
          + "  vec4 c = texture(uTex, vUV);\n"
          + "  if (uLutSize > 0.5) {\n"
          + "    float n = uLutSize;\n"
          + "    vec3 p = clamp(c.rgb, 0.0, 1.0);\n"
          + "    vec3 q = p * ((n - 1.0) / n) + vec3(0.5 / n);\n"
          + "    fragColor = vec4(texture(uLut, q).rgb, c.a);\n"
          + "  } else fragColor = c;\n"
          + "}\n";

  /**
   * Texture coordinates for the four destination corners (TL, TR, BR, BL). HWUI draws a
   * SurfaceTexture layer with the UV quad Rect(0, 1, 1, 0) — in GL convention (0,0) is the
   * BOTTOM-left corner, (0,1) the top-left. Grafika and WebRTC's SurfaceTexture quads use exactly
   * this mapping. Binding (0,0) to the top-left corner (the obvious Android 2D habit) mirrors /
   * rotates the whole preview relative to TextureView: a 90°-rotated rear stream turns sideways,
   * and a mirrored front stream turns fully upside down. These must match the framework exactly.
   */
  static final float[] QUAD_UV = {0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f};

  private final GLSurfaceView view;
  private final Callback callback;

  private int program;
  private int oesTex, lutTex;
  private volatile SurfaceTexture surface;
  private volatile boolean surfaceLive; // GL is up and the camera-facing SurfaceTexture exists
  private volatile boolean broken; // a GLThread failure happened; never touch GL again
  private final float[] texMatrix = new float[16];
  private final FloatBuffer quadUv;
  private ByteBuffer corners = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder());
  private float[] dstCorners = {0, 0, 1, 0, 1, 1, 0, 1}; // view-space 0..1 screen rect of the preview
  private int viewW = 1, viewH = 1;

  // LUT hand-off (uploaded on the GL thread)
  private byte[] pendingLut;
  private int pendingSize;

  // readback FBO
  private int fbo = -1, fboTex = -1, fboW, fboH;

  LivePreviewRenderer(GLSurfaceView view, Callback callback) {
    this.view = view;
    this.callback = callback;
    quadUv = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    quadUv.put(QUAD_UV).position(0);
  }

  /** Swallow any GLThread exception and ask the host to degrade to the TextureView path. */
  private synchronized void fail(Throwable error) {
    if (broken) return;
    broken = true;
    surfaceLive = false;
    Log.e(TAG, "GL viewfinder failed; requesting TextureView fallback", error);
    try {
      callback.glFailed(error);
    } catch (Throwable ignored) {
      // the host must not become the crash either
    }
  }

  /** Every GLThread task is wrapped: an uncaught exception there is a process-level crash. */
  private void queue(Runnable work) {
    try {
      view.queueEvent(
          () -> {
            if (broken) return;
            try {
              work.run();
            } catch (Throwable t) {
              fail(t);
            }
          });
    } catch (Throwable t) {
      fail(t);
    }
  }

  /** The camera-facing surface, while the GL surface is alive; null otherwise. */
  SurfaceTexture surface() {
    return surfaceLive ? surface : null;
  }

  boolean isLive() {
    return surfaceLive;
  }

  /** Screen rect the preview should cover (view coordinates, 0..1); mirrors TextureView's transform math. */
  void setCorners(float[] dstViewSpace, int vw, int vh) {
    queue(
        () -> {
          if (dstViewSpace == null || dstViewSpace.length < 8) return;
          dstCorners = dstViewSpace;
          viewW = Math.max(1, vw);
          viewH = Math.max(1, vh);
        });
  }

  /** Queue a new baked look LUT (RGB8, n³ entries); null clears the look (passthrough). */
  void setLut(byte[] rgb, int size) {
    queue(
        () -> {
          pendingLut = rgb;
          pendingSize = size;
        });
  }

  /**
   * Snapshot of the on-screen preview at the requested size, from a cache the GL thread refreshes
   * while consumers are asking. NEVER blocks: returns null until the first refresh lands (~150 ms
   * after someone starts asking), then serves the latest frame with a CPU scale. Blocking a caller
   * for GPU pixels is what killed v0.9.37 (ANRs: histogram/peaking/magnifier call on the UI thread).
   */
  Bitmap readPixels(int w, int h) {
    if (w <= 0 || h <= 0 || broken) return null;
    lastReadRequestMs = android.os.SystemClock.uptimeMillis();
    int[] px = cachePixels;
    if (px == null || !surfaceLive) return null;
    Bitmap src = Bitmap.createBitmap(px, cacheW, cacheH, Bitmap.Config.ARGB_8888);
    if (src.getWidth() == w && src.getHeight() == h) return src;
    return Bitmap.createScaledBitmap(src, w, h, true);
  }

  // readhead cache: magnifier-size so focus tools stay sharp; histogram/peaking downscale cheaply
  private static final int CACHE_W = 540, CACHE_H = 720;
  private static final int CACHE_STRIDE = 6; // refresh every Nth frame (~5 Hz at 30 fps)
  private volatile int[] cachePixels;
  private volatile int cacheW, cacheH;
  private volatile long lastReadRequestMs;

  // ── GL side ───────────────────────────────────────────────────────

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    if (broken) return;
    try {
    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
    program = buildProgram();
    oesTex = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
    SurfaceTexture old = surface;
    if (old != null) {
      try {
        old.release();
      } catch (Throwable ignored) {
      }
    }
    surface = new SurfaceTexture(oesTex);
    surfaceLive = true;
    lutTex = genTexture(GLES30.GL_TEXTURE_3D);
    if (pendingLut != null && pendingSize > 0) uploadLut();
    callback.surfaceReady(surface);
    } catch (Throwable t) {
      fail(t);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int w, int h) {
    if (broken) return;
    try {
      callback.sizeChanged(w, h);
    } catch (Throwable t) {
      fail(t);
    }
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    if (broken || !surfaceLive) return;
    try {
      try {
        surface.updateTexImage();
        surface.getTransformMatrix(texMatrix);
      } catch (Throwable t) {
        return; // no frame yet
      }
      if (pendingLut != null || pendingSize == 0) uploadLut();
      drawScene(viewW, viewH);
      long now = android.os.SystemClock.uptimeMillis();
      if (now - lastReadRequestMs < 2500 && ++refreshCounter % CACHE_STRIDE == 0) refreshCache();
    } catch (Throwable t) {
      fail(t);
    }
  }

  private int refreshCounter;

  /** Draws the scene (OES sample → optional LUT) into the current framebuffer of view size vw×vh. */
  private void drawScene(int vw, int vh) {
    GLES30.glViewport(0, 0, Math.max(1, vw), Math.max(1, vh));
    GLES30.glUseProgram(program);
    // dst is in pixels of the original view; NDC is derived from that view, so the quad keeps
    // the same relative placement/scaling even when rendering into the smaller readback FBO.
    FloatBuffer vb = corners.asFloatBuffer();
    vb.clear();
    for (int i = 0; i < 8; i += 2) {
      vb.put(dstCorners[i] / viewW * 2f - 1f);
      vb.put(1f - dstCorners[i + 1] / viewH * 2f);
    }
    vb.position(0);
    quadUv.position(0);
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, vb);
    GLES30.glEnableVertexAttribArray(0);
    GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, quadUv);
    GLES30.glEnableVertexAttribArray(1);
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0);
    GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0);
    GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex);
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1);
    GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uLutSize"), currentLutSize);
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);
  }

  private int currentLutSize; // 0 = passthrough

  private void uploadLut() {
    byte[] rgb = pendingLut;
    int n = pendingSize;
    if (rgb == null || n < 1) {
      // null/empty means passthrough: unfilm every pixel
      pendingLut = null;
      pendingSize = -1;
      currentLutSize = 0;
      return;
    }
    try {
      GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex);
      GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
      GLES30.glTexImage3D(
          GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, n, n, n, 0, GLES30.GL_RGB,
          GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(rgb));
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE);
      currentLutSize = n;
    } catch (Throwable t) {
      Log.w(TAG, "LUT upload failed — passthrough", t);
      currentLutSize = 0;
    }
    pendingLut = null;
    pendingSize = -1;
  }

  private void refreshCache() {
    try {
      int[] px = renderToPixels(CACHE_W, CACHE_H);
      if (px != null) {
        cachePixels = px;
        cacheW = CACHE_W;
        cacheH = CACHE_H;
      }
    } catch (Throwable t) {
      Log.w(TAG, "readback cache refresh failed", t);
    }
  }

  private int[] renderToPixels(int w, int h) {
    if (fbo == -1 || fboW != w || fboH != h) {
      if (fbo != -1) {
        GLES30.glDeleteFramebuffers(1, new int[] {fbo}, 0);
        GLES30.glDeleteTextures(1, new int[] {fboTex}, 0);
      }
      int[] ids = new int[1];
      GLES30.glGenFramebuffers(1, ids, 0);
      fbo = ids[0];
      fboTex = genTexture(GLES30.GL_TEXTURE_2D);
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex);
      GLES30.glTexImage2D(
          GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA,
          GLES30.GL_UNSIGNED_BYTE, null);
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
      GLES30.glFramebufferTexture2D(
          GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0);
      fboW = w;
      fboH = h;
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
    drawScene(w, h);
    ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
    GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    int[] argb = new int[w * h];
    byte[] px = buf.array();
    for (int y = 0; y < h; y++) {
      int rowOut = (h - 1 - y) * w; // GL rows are bottom-up
      int rowIn = y * w * 4;
      for (int x = 0; x < w; x++) {
        int o = rowIn + x * 4;
        argb[rowOut + x] =
            0xff000000 | ((px[o] & 0xff) << 16) | ((px[o + 1] & 0xff) << 8) | (px[o + 2] & 0xff);
      }
    }
    return argb;
  }

  void releaseSurface() {
    queue(
        () -> {
          if (surface != null) {
            try {
              surface.release();
            } catch (Throwable ignored) {
            }
            surface = null;
          }
          surfaceLive = false;
          callback.surfaceGone();
        });
  }

  private static int genTexture(int target) {
    int[] ids = new int[1];
    GLES30.glGenTextures(1, ids, 0);
    GLES30.glBindTexture(target, ids[0]);
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    return ids[0];
  }

  private static int shader(int type, String src) throws RuntimeException {
    int s = GLES30.glCreateShader(type);
    GLES30.glShaderSource(s, src);
    GLES30.glCompileShader(s);
    int[] ok = new int[1];
    GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
    if (ok[0] == 0) {
      String why = GLES30.glGetShaderInfoLog(s);
      GLES30.glDeleteShader(s);
      throw new RuntimeException("shader: " + why);
    }
    return s;
  }

  private static int buildProgram() {
    int vs = shader(GLES30.GL_VERTEX_SHADER, VERTEX);
    int fs = shader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT);
    int p = GLES30.glCreateProgram();
    GLES30.glAttachShader(p, vs);
    GLES30.glAttachShader(p, fs);
    GLES30.glLinkProgram(p);
    int[] ok = new int[1];
    GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
    if (ok[0] == 0) throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(p));
    GLES30.glDeleteShader(vs);
    GLES30.glDeleteShader(fs);
    return p;
  }
}
