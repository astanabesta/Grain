package dev.grain.photo;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.view.Surface;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * Remote viewfinder sender: the whole screen (the app is portrait-locked, so what you see is what
 * the PC sees) captured at the GPU level and hardware-encoded to H.264, streamed over Wi-Fi UDP,
 * plus a small JSON metadata sideband (~10 Hz).
 *
 * <p>The capture path is the zero-copy one used by scrcpy/Monect: {@code MediaProjection →
 * VirtualDisplay → MediaCodec input Surface}. No GL readback, no CPU frame copies, and the stream
 * keeps running during photo capture.
 *
 * <p>Wire protocol (plain datagrams, one client at a time, LAN only):
 *
 * <ul>
 *   <li>client → phone {@code "GRV1 SUB <token>"} subscribes (starts with a fresh keyframe);
 *       wrong token is ignored. {@code "GRV1 BYE"} unsubscribes. A broadcast {@code "GRV1 FIND"}
 *       always gets a unicast {@code "GRV1 HERE <wifi-ip>"} answer.
 *   <li>phone → client video packets: 10-byte header {@code GRV f f f frameSeq(be32) chunkTot
 *       chunkIdx flags} + Annex-B payload chunk (≤1300 B). flags bit0 = keyframe.
 *   <li>phone → client metadata: {@code "GRM1 "}+JSON, ~10 Hz regardless of video.
 * </ul>
 */
final class RemoteStream {
  /** Stream state from the camera side (exposure/recipe/level as wire JSON). */
  interface Source {
    /** Exposure/recipe/level state as wire JSON (built by the activity). Must include "cap". */
    String metaJson();
  }

  private static final int PORT = 4361;
  private static final String HELLO = "GRV1 ";
  private static final int MAX_PAYLOAD = 1300;
  private static final int BITRATE = 6_000_000;
  private static final int FPS = 30;
  static final long CLIENT_TTL_MS = 4000; // metadata keep-alive keeps the client alive

  private final Source source;
  private final MediaProjection projection;
  private final int width, height, dpi; // virtual display size (multiple of 16)

  private volatile boolean running;
  private volatile InetSocketAddress client;
  private volatile long lastClientMs;
  private Thread thread;
  private DatagramSocket sock;
  private MediaCodec codec;
  private Surface inputSurface;
  private VirtualDisplay display;
  private long frameSeq;
  private byte[] csd; // Annex-B SPS+PPS, prepended at every keyframe that lacks them

  RemoteStream(Source source, MediaProjection projection, int width, int height, int dpi) {
    this.source = source;
    this.projection = projection;
    this.width = width & ~15;
    this.height = height & ~15;
    this.dpi = dpi;
  }

  synchronized void start(String token, Runnable error) {
    if (running) return;
    running = true;
    thread = new Thread(() -> loop(token == null ? "" : token, error), "grain-remote");
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
      sock.setSoTimeout(100);

      // Low-latency encoder, the game-streaming recipe: realtime priority, ≤1 frame of encoder
      // latency, constrained baseline (no B-frames so nothing gets held hostage in the codec),
      // sync frame when a client joins, keyframes every 2 s as safety.
      MediaFormat fmt =
          MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
      fmt.setInteger(
          MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
      fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
      fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
      fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
      fmt.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
      fmt.setInteger(MediaFormat.KEY_LATENCY, 1); // ≤1 frame held by the encoder
      codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
      codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      inputSurface = codec.createInputSurface();
      codec.start();

      display =
          projection.createVirtualDisplay(
              "grain-remote",
              width,
              height,
              dpi,
              DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
              inputSurface,
              null,
              null);

      long lastMeta = 0;
      byte[] in = new byte[1024];
      DatagramPacket hello = new DatagramPacket(in, in.length);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      byte[] packet = new byte[MAX_PAYLOAD + 10];
      while (running) {
        // client handshake / keep-alive / discovery
        try {
          hello.setData(in);
          sock.receive(hello);
          String msg = new String(hello.getData(), 0, hello.getLength(), "UTF-8").trim();
          if (msg.equals("GRV1 BYE")) {
            client = null;
          } else if (msg.equals("GRV1 FIND")) {
            // Monect-style zero-config: the viewer broadcasts this and we answer with our IP.
            String ip = localIp();
            if (ip != null) {
              byte[] here = ("GRV1 HERE " + ip).getBytes("UTF-8");
              sock.send(new DatagramPacket(here, here.length, hello.getAddress(), hello.getPort()));
            }
          } else if (msg.startsWith(HELLO + "SUB ")) {
            if (token.isEmpty() || msg.substring((HELLO + "SUB ").length()).trim().equals(token)) {
              client = new InetSocketAddress(hello.getAddress(), hello.getPort());
              lastClientMs = System.currentTimeMillis();
              requestSyncFrame(); // a new viewer never waits for the next scheduled IDR
            }
          }
        } catch (SocketTimeoutException ignored) {
        }

        // metadata sideband: ~10 Hz whenever a client is subscribed
        long now = System.currentTimeMillis();
        if (client != null && now - lastMeta >= 100) {
          lastMeta = now;
          byte[] meta = ("GRM1 " + source.metaJson()).getBytes("UTF-8");
          sock.send(new DatagramPacket(meta, meta.length, client));
        }

        // drain everything the encoder has — the surface feeds it at display rate
        int outIdx;
        while ((outIdx = codec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
          if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            byte[] f = readCsd(codec.getOutputFormat());
            if (f != null) csd = f;
            continue;
          }
          if (outIdx < 0) continue;
          java.nio.ByteBuffer buf = codec.getOutputBuffer(outIdx);
          if (buf != null && info.size > 0 && client != null) {
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
      try {
        if (display != null) display.release();
      } catch (Throwable ignored) {
      }
      try {
        if (codec != null) codec.stop();
      } catch (Throwable ignored) {
      }
      try {
        if (codec != null) codec.release();
      } catch (Throwable ignored) {
      }
      try {
        if (inputSurface != null) inputSurface.release();
      } catch (Throwable ignored) {
      }
      try {
        if (sock != null) sock.close();
      } catch (Throwable ignored) {
      }
    }
  }

  /** Ask the encoder for an IDR "soon" (called when a viewer subscribes). */
  private void requestSyncFrame() {
    try {
      android.os.Bundle b = new android.os.Bundle();
      b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
      codec.setParameters(b);
    } catch (Throwable ignored) {
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

  /** A csd blob may be Annex-B already or an avcc-style chain of length-prefixed NALs. */
  private static byte[] annexB(byte[] d) {
    if (d.length == 0 || hasStartCode(d, 0)) return d;
    // avcc chain: while (bytes) { be32 length, NAL } — parse strictly; fall back to single NAL.
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(d.length + 8);
    int off = 0, n = 0;
    while (off + 4 <= d.length) {
      int len =
          ((d[off] & 0xff) << 24) | ((d[off + 1] & 0xff) << 16) | ((d[off + 2] & 0xff) << 8)
              | (d[off + 3] & 0xff);
      if (len <= 0 || len > d.length - off - 4) break;
      out.write(0); out.write(0); out.write(0); out.write(1);
      out.write(d, off + 4, len);
      off += 4 + len;
      n++;
    }
    if (n > 0 && off == d.length) return out.toByteArray();
    // last resort: treat the whole blob as one bare NAL
    byte[] single = new byte[d.length + 4];
    single[2] = 0; single[3] = 1; // "00 00 00 01"
    System.arraycopy(d, 0, single, 4, d.length);
    return single;
  }

  /** True when the bytestream's first NAL is an SPS (type 7). */
  private static boolean startsWithSps(byte[] d) {
    if (!hasStartCode(d, 0)) return false;
    int hdr = d[2] == 1 ? 3 : 4;
    return d.length > hdr && (d[hdr] & 0x1f) == 7;
  }

  /**
   * This device's LAN IPv4 (for the SET UP readout); null when offline. Prefers a Wi-Fi-looking
   * interface — with mobile data on, a naive scan happily returns the cellular address (or a
   * CGNAT 100.64.x.x address) that a PC on the home network can never reach.
   */
  static String localIp() {
    String fallback = null;
    try {
      java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
      while (ifs != null && ifs.hasMoreElements()) {
        java.net.NetworkInterface iface = ifs.nextElement();
        if (!iface.isUp() || iface.isLoopback()) continue;
        String name = iface.getName().toLowerCase(Locale.US);
        boolean wifi = name.startsWith("wlan") || name.contains("wifi") || name.startsWith("ap");
        boolean cellular = name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp");
        java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
        while (addrs.hasMoreElements()) {
          java.net.InetAddress a = addrs.nextElement();
          if (a.isLoopbackAddress() || !(a instanceof java.net.Inet4Address)) continue;
          String ip = a.getHostAddress();
          if (ip == null) continue;
          // skip CGNAT 100.64.0.0/10 (carrier-grade NAT): unroutable from home PCs
          if (ip.startsWith("100.")) {
            try {
              int b = Integer.parseInt(ip.substring(4, ip.indexOf('.', 4)));
              if (b >= 64 && b <= 127) continue;
            } catch (Throwable ignored) {
            }
          }
          if (wifi) return ip;             // best answer: a Wi-Fi address
          if (fallback == null && !cellular) fallback = ip;
          if (fallback == null) fallback = ip; // better than nothing
        }
      }
    } catch (Throwable ignored) {
    }
    return fallback;
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
