package dev.grain.photo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** Receives {@link PackageInstaller} session commit results for Grain's in-app updater. */
public final class UpdateReceiver extends BroadcastReceiver {
  public static final String ACTION_INSTALL_STATUS = "dev.grain.photo.ACTION_INSTALL_STATUS";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
    int status =
        intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      Intent confirm = extraIntent(intent);
      if (confirm != null) {
        if (AppUpdater.tryLaunchFromForeground(confirm)) {
          AppUpdater.dispatchInstallStatus(status, "Confirm the update to finish installing");
          return;
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
          context.startActivity(confirm);
          AppUpdater.dispatchInstallStatus(status, "Confirm the update to finish installing");
          return;
        } catch (Exception e) {
          detail = e.getMessage();
          status = PackageInstaller.STATUS_FAILURE;
        }
      }
    }
    AppUpdater.dispatchInstallStatus(status, detail);
    if (status == PackageInstaller.STATUS_SUCCESS) {
      Toast.makeText(context, "Grain updated", Toast.LENGTH_SHORT).show();
    } else if (status != PackageInstaller.STATUS_FAILURE_ABORTED
        && !AppUpdater.isSystemBlockedAbort(status, detail)) {
      String msg =
          detail == null || detail.isEmpty() ? "Update failed (" + status + ")" : "Update failed: " + detail;
      Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
    }
  }

  @SuppressWarnings("deprecation")
  private static Intent extraIntent(Intent source) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return source.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
    }
    return source.getParcelableExtra(Intent.EXTRA_INTENT);
  }
}
