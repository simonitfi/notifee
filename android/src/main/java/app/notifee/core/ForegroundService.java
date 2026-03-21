package app.notifee.core;

/*
 * Copyright (c) 2016-present Invertase Limited & Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this library except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import android.annotation.SuppressLint;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import app.notifee.core.event.ForegroundServiceEvent;
import app.notifee.core.event.NotificationEvent;
import app.notifee.core.interfaces.MethodCallResult;
import app.notifee.core.model.NotificationModel;

public class ForegroundService extends Service {
  private static final String TAG = "ForegroundService";
  private static final int DEFAULT_FOREGROUND_NOTIFICATION_ID = 1;
  private static final String CHANNEL_ID = "foreground_service_channel";
  public static final String START_FOREGROUND_SERVICE_ACTION =
      "app.notifee.core.ForegroundService.START";
  public static final String STOP_FOREGROUND_SERVICE_ACTION =
      "app.notifee.core.ForegroundService.STOP";

  public static String mCurrentNotificationId = null;

  public static int mCurrentForegroundServiceType = -1;

  static void start(int hashCode, Notification notification, Bundle notificationBundle) {
    Intent intent = new Intent(ContextHolder.getApplicationContext(), ForegroundService.class);
    intent.setAction(START_FOREGROUND_SERVICE_ACTION);
    intent.putExtra("hashCode", hashCode);
    intent.putExtra("notification", notification);
    intent.putExtra("notificationBundle", notificationBundle);

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextHolder.getApplicationContext().startForegroundService(intent);
      } else {
        ContextHolder.getApplicationContext().startService(intent);
      }
    } catch (Exception e) {
      // On Android 12+, ForegroundServiceStartNotAllowedException is thrown when
      // the OS denies the foreground service start (e.g. app no longer in a
      // high-priority state). Swallow gracefully instead of crashing.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
          && e instanceof ForegroundServiceStartNotAllowedException) {
        Logger.w(TAG, "Foreground service start not allowed by OS: " + e.getMessage());
      } else {
        throw e;
      }
    }
  }

  static void stop() {
    Intent intent = new Intent(ContextHolder.getApplicationContext(), ForegroundService.class);
    intent.setAction(STOP_FOREGROUND_SERVICE_ACTION);

    try {
      // Call start service first with stop action
      ContextHolder.getApplicationContext().startService(intent);
    } catch (IllegalStateException illegalStateException) {
      // try to stop with stopService command
      ContextHolder.getApplicationContext().stopService(intent);
    } catch (Exception exception) {
      Logger.e(TAG, "Unable to stop foreground service", exception);
    }
  }

  @SuppressLint({"ForegroundServiceType", "MissingPermission"})
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // Null intent — system restarted service after kill; nothing to do
    if (intent == null) {
      return START_NOT_STICKY;
    }

    // Check if action is to stop the foreground service
    if (STOP_FOREGROUND_SERVICE_ACTION.equals(intent.getAction())) {
      // Android 12+ requires startForeground() before stopSelf() when the
      // service was started with startForegroundService(). Post a dummy
      // notification so the OS constraint is satisfied.
      if (mCurrentNotificationId != null) {
        ensureForegroundServiceRunning();
      }
      stopSelf();
      mCurrentNotificationId = null;
      mCurrentForegroundServiceType = -1;
      return START_NOT_STICKY;
    }

    Bundle extras = intent.getExtras();

    if (extras != null) {
      // Hash code is sent to service to ensure it is kept the same
      int hashCode = extras.getInt("hashCode");
      Notification notification = extras.getParcelable("notification");
      Bundle bundle = extras.getBundle("notificationBundle");

      if (notification != null & bundle != null) {
        NotificationModel notificationModel = NotificationModel.fromBundle(bundle);

        if (mCurrentNotificationId == null) {
          mCurrentNotificationId = notificationModel.getId();

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int foregroundServiceType = notificationModel.getAndroid().getForegroundServiceType();
            startForeground(hashCode, notification, foregroundServiceType);
            mCurrentForegroundServiceType = foregroundServiceType;
          } else {
            startForeground(hashCode, notification);
          }

          // On headless task complete
          final MethodCallResult<Void> methodCallResult =
              (e, aVoid) -> {
                stopForeground(true);
                mCurrentNotificationId = null;
                mCurrentForegroundServiceType = -1;
              };

          ForegroundServiceEvent foregroundServiceEvent =
              new ForegroundServiceEvent(notificationModel, methodCallResult);

          EventBus.post(foregroundServiceEvent);
        } else {
          if (mCurrentNotificationId.equals(notificationModel.getId())) {
            boolean shouldPostNotificationAgain = true;
            // find if we need to start the service again if the type was changed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              int foregroundServiceType = notificationModel.getAndroid().getForegroundServiceType();
              if (foregroundServiceType != mCurrentForegroundServiceType) {
                startForeground(hashCode, notification, foregroundServiceType);
                mCurrentForegroundServiceType = foregroundServiceType;
                shouldPostNotificationAgain = false;
              }
            }
            if (shouldPostNotificationAgain) {
              NotificationManagerCompat.from(ContextHolder.getApplicationContext())
                  .notify(hashCode, notification);
            }
          } else {
            EventBus.post(
                new NotificationEvent(NotificationEvent.TYPE_FG_ALREADY_EXIST, notificationModel));
          }
        }
      }
    }

    return START_NOT_STICKY;
  }

  /**
   * Ensures startForeground() has been called before stopSelf(), preventing
   * ForegroundServiceDidNotStartInTimeException on Android 12+.
   */
  @SuppressLint("ForegroundServiceType")
  private void ensureForegroundServiceRunning() {
    Notification dummyNotification = createDummyNotification();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mCurrentForegroundServiceType != -1) {
      startForeground(DEFAULT_FOREGROUND_NOTIFICATION_ID, dummyNotification, mCurrentForegroundServiceType);
    } else {
      startForeground(DEFAULT_FOREGROUND_NOTIFICATION_ID, dummyNotification);
    }
  }

  /**
   * Creates a minimal low-priority notification to satisfy the startForeground()
   * requirement during service shutdown.
   */
  private Notification createDummyNotification() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
          CHANNEL_ID, "Foreground Service", NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Required for foreground service shutdown");
      NotificationManager notificationManager =
          (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      if (notificationManager != null) {
        notificationManager.createNotificationChannel(channel);
      }
    }

    int iconResId = getApplicationContext().getResources().getIdentifier(
        "notification_icon", "drawable", getApplicationContext().getPackageName());
    if (iconResId == 0) {
      iconResId = getApplicationContext().getResources().getIdentifier(
          "ic_launcher", "mipmap", getApplicationContext().getPackageName());
    }

    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("")
        .setContentText("")
        .setSmallIcon(iconResId)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)
        .build();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
