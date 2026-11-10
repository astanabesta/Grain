package dev.grain.photo;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * Remote viewfinder sender: the masked photo frame hardware-encoded to H.264 and streamed over
 * Wi-Fi UDP, plus a small JSON metadata sideband (exposure, recipe, level, grid, shot pulse).
 *
 * <p>Wire protocol (plain datagrams, one client at a time, LAN only):
 *
 * <ul>
 *   <li>client → phone {@code "GRV1 SUB <token>"} subscribes; wrong token is ignored. {@code
 *       "GRV1 BYE"} unsubscribes.
 *   <li>phone → client video packets: 10-byte header {@code GRV f f f frameSeq(be32) chunkTot
 *       chunkIdx flags} + Annex-B payload chunk (≤1300 B). flags bit0 = keyframe.
 *   <li>phone → client metadata: {@code "GRM1 "}+JSON, ~10 Hz regardless of video.
 * </ul>
 *
 * The stream is paused (video only — metadata keeps flowing) while a shot is being captured so the
 * encode read-back never touches the RAW path.
 */
final class RemoteStream {
  /** Supplies encoded-sized frames and stream state from the camera side. */
  interface Source {
    /** A fresh frame scaled to (w,h), or null if the preview cannot be read this round. */
    Bitmap grab(int w, int h);

    /** Exposure/recipe/level state as wire JSON (built by the activity). */
    String metaJson();

    /** True while a capture is in flight: video pauses, metadata continues. */
    boolean captureBusy();
  }

  private static final int PORT = 4361;
  private static final String HELLO = "GRV1 ";
  private static final int MAX_PAYLOAD = 1300;
  static final long CLIENT_TTL_MS = 4000; // metadata keep-alive keeps the client alive

  private final Source source;
  private final int width, height; // encoder size (multiple of 16)
  private final int fps = 20;

  private volatile boolean running;
  private volatile InetSocketAddress client;
  private volatile long lastClientMs;
  private Thread thread;
  private DatagramSocket sock;
  private MediaCodec codec;
  private long frameSeq;
  private byte[] csd; // Annex-B SPS+PPS, prepended at every keyframe that lacks them

  RemoteStream(Source source, int width, int height) {
    this.source = source;
    this.width = width & ~15;
    this.height = height & ~15;
  }

  synchronized void start(String token, Runnable error) {
    if (running) return;
    running = true;
    thread =
        new Thread(() -> loop(token == null ? "" : token, error), "grain-remote");
    thread.setDaemon(true);
    thread.start();
  }

  synchronized void stop() {
    running = false;
    client = null;
    if (thread != null) thread.interrupt();
  }

  boolean isLive() {
    InetSocketAddress c = client;
    return running && c != null && System.currentTimeMillis() - lastClientMs < CLIENT_TTL_MS;
  }

  private void loop(String token, Runnable error) {
    try {
      sock = new DatagramSocket(PORT);
      sock.setSoTimeout(250);
      MediaFormat fmt =
          MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
      fmt.setInteger(
          MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
      fmt.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
      fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
      fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
      fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
      codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
      codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      codec.start();
      Bitmap frame = null;
      long lastMeta = 0, lastVideo = 0;
      byte[] in = new byte[1024];
      DatagramPacket hello = new DatagramPacket(in, in.length);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      byte[] yuv = new byte[width * height + width * height / 2];
      byte[] packet = new byte[MAX_PAYLOAD + 10];
      while (running) {
        // client handshake / keep-alive
        try {
          hello.setData(in);
          sock.receive(hello);
          String msg = new String(hello.getData(), 0, hello.getLength(), "UTF-8").trim();
          if (msg.equals("GRV1 BYE")) {
            client = null;
          } else if (msg.startsWith(HELLO + "SUB ")) {
            if (token.isEmpty() || msg.substring((HELLO + "SUB ").length()).trim().equals(token)) {
              client = new InetSocketAddress(hello.getAddress(), hello.getPort());
              lastClientMs = System.currentTimeMillis();
            }
          }
        } catch (SocketTimeoutException ignored) {
        }
        if (client == null) {
          continue;
        }
        long now = System.currentTimeMillis();
        if (now - lastMeta >= 100) {
          lastMeta = now;
          byte[] meta = ("GRM1 " + source.metaJson()).getBytes("UTF-8");
          sock.send(new DatagramPacket(meta, meta.length, client));
        }
        if (source.captureBusy() || now - lastVideo < 1000 / fps) continue;
        lastVideo = now;
        frame = source.grab(width, height);
        if (frame == null) continue;
        int n = toYuv420(frame, yuv);
        int inIdx = codec.dequeueInputBuffer(5000);
        if (inIdx < 0) continue;
        codec.getInputBuffer(inIdx).clear();
        codec.getInputBuffer(inIdx).put(yuv, 0, n);
        codec.queueInputBuffer(inIdx, 0, n, now * 1000, 0);
        int outIdx;
        while ((outIdx = codec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
          if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            byte[] f = readCsd(codec.getOutputFormat());
            if (f != null) csd = f;
            continue;
          }
          if (outIdx < 0) continue;
          java.nio.ByteBuffer buf = codec.getOutputBuffer(outIdx);
          if (buf != null && info.size > 0) {
            byte[] nal = new byte[info.size];
            buf.get(nal);
            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
              csd = annexB(nal); // keep it; sent with the next IDR instead of bare
            } else {
              // Some encoders never repeat SPS/PPS in-band; the viewer decoder needs them.
              if (key && csd != null && !startsWithSps(nal)) {
                byte[] both = new byte[csd.length + nal.length];
                System.arraycopy(csd, 0, both, 0, csd.length);
                System.arraycopy(nal, 0, both, csd.length, nal.length);
                nal = both;
              }
              sendFrame(nal, key, sock, packet);
            }
          }
          codec.releaseOutputBuffer(outIdx, false);
        }
      }
    } catch (Throwable t) {
      running = false;
      if (error != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(error);
    } finally {
      try { if (codec != null) codec.stop(); } catch (Throwable ignored) {}
      try { if (codec != null) codec.release(); } catch (Throwable ignored) {}
      try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
    }
  }

  /** Split one encoder output (Annex-B bytestream for a frame) into ≤1300 B datagrams. */
  private void sendFrame(byte[] data, boolean key, DatagramSocket sock, byte[] scratch)
      throws java.io.IOException {
    int chunks = Math.max(1, (data.length + MAX_PAYLOAD - 1) / MAX_PAYLOAD);
    if (chunks > 250) return; // pathological; drop
    frameSeq++;
    for (int i = 0; i < chunks; i++) {
      int off = i * MAX_PAYLOAD, len = Math.min(MAX_PAYLOAD, data.length - off);
      scratch[0] = 'G'; scratch[1] = 'R'; scratch[2] = 'V';
      scratch[3] = (byte) (frameSeq >>> 24);
      scratch[4] = (byte) (frameSeq >>> 16);
      scratch[5] = (byte) (frameSeq >>> 8);
      scratch[6] = (byte) frameSeq;
      scratch[7] = (byte) chunks;
      scratch[8] = (byte) i;
      scratch[9] = (byte) (key ? 1 : 0);
      System.arraycopy(data, off, scratch, 10, len);
      sock.send(new DatagramPacket(scratch, 10 + len, client));
    }
  }

  /** csd-0/csd-1 (SPS, PPS) from the output format, concatenated in Annex-B form; null if absent. */
  private static byte[] readCsd(MediaFormat f) {
    java.nio.ByteBuffer sps = f.getByteBuffer("csd-0"), pps = f.getByteBuffer("csd-1");
    if (sps == null) return null;
    byte[] a = annexB(bytes(sps));
    byte[] b = pps == null ? new byte[0] : annexB(bytes(pps));
    byte[] both = new byte[a.length + b.length];
    System.arraycopy(a, 0, both, 0, a.length);
    System.arraycopy(b, 0, both, a.length, b.length);
    return both;
  }

  private static byte[] bytes(java.nio.ByteBuffer buf) {
    byte[] d = new byte[buf.remaining()];
    buf.duplicate().get(d);
    return d;
  }

  private static boolean hasStartCode(byte[] d, int off) {
    return d.length >= off + 4 && d[off] == 0 && d[off + 1] == 0
        && (d[off + 2] == 1 || (d[off + 2] == 0 && d[off + 3] == 1));
  }

  /** A csd buffer may arrive as a bare NAL (avcc style); normalize to Annex-B with a start code. */
  private static byte[] annexB(byte[] d) {
    if (d.length == 0 || hasStartCode(d, 0)) return d;
    byte[] out = new byte[d.length + 4];
    out[2] = 0; out[3] = 1; // "00 00 00 01"
    System.arraycopy(d, 0, out, 4, d.length);
    return out;
  }

  /** True when the bytestream's first NAL is an SPS (type 7). */
  private static boolean startsWithSps(byte[] d) {
    if (!hasStartCode(d, 0)) return false;
    int hdr = d[2] == 1 ? 3 : 4;
    return d.length > hdr && (d[hdr] & 0x1f) == 7;
  }

  /** ARGB bitmap → I420 (yuv420p). Encoders accept this exact packing via YUV420Flexible. */
  static int toYuv420(Bitmap b, byte[] out) {
    int w = b.getWidth(), h = b.getHeight();
    int[] argb = new int[w * h];
    b.getPixels(argb, 0, w, 0, 0, w, h);
    int yLen = w * h, uLen = yLen / 4;
    int yi = 0, ui = yLen, vi = yLen + uLen;
    for (int y = 0; y < h; y += 2) {
      int row = y * w, row2 = (y + 1) * w;
      for (int x = 0; x < w; x += 2) {
        int p00 = argb[row + x], p01 = argb[row + x + 1], p10 = argb[row2 + x], p11 = argb[row2 + x + 1];
        int r00 = (p00 >> 16) & 255, g00 = (p00 >> 8) & 255, b00 = p00 & 255;
        int r01 = (p01 >> 16) & 255, g01 = (p01 >> 8) & 255, b01 = p01 & 255;
        int r10 = (p10 >> 16) & 255, g10 = (p10 >> 8) & 255, b10 = p10 & 255;
        int r11 = (p11 >> 16) & 255, g11 = (p11 >> 8) & 255, b11 = p11 & 255;
        out[row + x] = (byte) lum(r00, g00, b00);
        out[row + x + 1] = (byte) lum(r01, g01, b01);
        out[row2 + x] = (byte) lum(r10, g10, b10);
        out[row2 + x + 1] = (byte) lum(r11, g11, b11);
        int r = (r00 + r01 + r10 + r11) / 4, g = (g00 + g01 + g10 + g11) / 4, bb = (b00 + b01 + b10 + b11) / 4;
        out[ui++] = (byte) clamp(128 + (-38 * r - 74 * g + 112 * bb) / 256);
        out[vi++] = (byte) clamp(128 + (112 * r - 94 * g - 18 * bb) / 256);
      }
    }
    return yLen + 2 * uLen;
  }

  private static int lum(int r, int g, int b) {
    return clamp((66 * r + 129 * g + 25 * b) / 256 + 16);
  }

  private static int clamp(int v) {
    return v < 0 ? 0 : Math.min(v, 255);
  }

  /** This device's LAN IPv4 (for the SET UP readout); null when offline. */
  static String localIp() {
    try {
      java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
      while (ifs != null && ifs.hasMoreElements()) {
        java.net.NetworkInterface iface = ifs.nextElement();
        java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
        while (addrs.hasMoreElements()) {
          java.net.InetAddress a = addrs.nextElement();
          if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return a.getHostAddress();
        }
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  /** A short human-typeable stream token. */
  static String newToken() {
    final char[] abc = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    StringBuilder s = new StringBuilder();
    java.util.Random r = new java.util.Random();
    for (int i = 0; i < 5; i++) s.append(abc[r.nextInt(abc.length)]);
    return s.toString();
  }

  static int port() {
    return PORT;
  }

  static String describe(String ip, String token) {
    return ip == null ? "no Wi-Fi" : String.format(Locale.US, "%s:%d · %s", ip, PORT, token);
  }
}
