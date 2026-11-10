package dev.grain.photo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Foreground service that owns the {@link MediaProjection} while the remote viewfinder streams.
 *
 * <p>Android 14+ (targetSdk 34+) rules this: the service must already be in the foreground with
 * type {@code mediaProjection} before {@code getMediaProjection} is called, so the consent result
 * is delivered here via intent extras and the projection is created in {@link #onStartCommand}.
 *
 * <p>The projection survives the activity pausing (no re-consent on return); it dies with the
 * process or when {@link #ACTION_STOP} is sent. Lifecycle events are surfaced to {@link
 * MainActivity} through the static hooks {@link #setOnReady} / {@link #setOnStopped}.
 */
public final class RemoteService extends Service {
  public static final String ACTION_START = "dev.grain.photo.REMOTE_START";
  public static final String ACTION_STOP = "dev.grain.photo.REMOTE_STOP";
  private static final String CHANNEL = "remote";
  private static final int NOTIF_ID = 41;

  private static volatile MediaProjection projection;
  private static Runnable onReady, onStopped;

  static MediaProjection current() {
    return projection;
  }

  static void setOnReady(Runnable r) {
    onReady = r;
  }

  static void setOnStopped(Runnable r) {
    onStopped = r;
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || ACTION_STOP.equals(intent.getAction())) {
      releaseProjection();
      stopSelf();
      return START_NOT_STICKY;
    }
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm.getNotificationChannel(CHANNEL) == null) {
      nm.createNotificationChannel(
          new NotificationChannel(CHANNEL, "Remote view", NotificationManager.IMPORTANCE_MIN));
    }
    Notification n =
        new Notification.Builder(this, CHANNEL)
            .setContentTitle("Grain remote view")
            .setContentText("Sharing this screen to your PC over Wi-Fi")
            .setSmallIcon(R.drawable.notify_remote)
            .setOngoing(true)
            .build();
    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    int code = intent.getIntExtra("code", 0);
    Intent data = intent.getParcelableExtra("data");
    if (code != 0 && data != null) {
      projection = getSystemService(MediaProjectionManager.class).getMediaProjection(code, data);
      projection.registerCallback(
          new MediaProjection.Callback() {
            @Override
            public void onStop() {
              releaseProjection();
            }
          },
          new Handler(Looper.getMainLooper()));
      Runnable r = onReady;
      if (r != null) new Handler(Looper.getMainLooper()).post(r);
    }
    return START_STICKY;
  }

  private void releaseProjection() {
    MediaProjection p = projection;
    projection = null;
    if (p != null) {
      try {
        p.stop();
      } catch (Throwable ignored) {
      }
    }
    Runnable r = onStopped;
    if (r != null) new Handler(Looper.getMainLooper()).post(r);
  }

  @Override
  public void onDestroy() {
    releaseProjection();
    super.onDestroy();
  }
}
