package com.pmdgaming.airlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MonitorService extends Service {
    private static final String CHANNEL_ID = "airlock_monitor";
    private static final int NOTIFICATION_ID = 4112;
    private static final long POLL_MS = 500L;
    private static final long EVENT_WINDOW_MS = 60_000L;

    private volatile boolean running;
    private Thread worker;
    private String foregroundPackage;
    private String activeProtectedPackage;
    private long lastProcessedEventTime;
    private Boolean airplaneStateBeforeSession;
    private String lastError;

    public static void start(Context context) {
        Intent intent = new Intent(context, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
            // UI will still expose the missing permission/state.
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("AirLock izleme başlatılıyor…"));
        running = true;
        worker = new Thread(this::loop, "AirLock-Foreground-Monitor");
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void loop() {
        while (running) {
            try {
                Set<String> protectedApps = getProtectedApps();
                if (protectedApps.isEmpty()) {
                    exitProtectedSession();
                    stopSelf();
                    return;
                }

                if (!hasUsageAccess()) {
                    setError("Kullanım erişimi kapalı");
                } else if (!isDeviceOwner()) {
                    setError("AirLock Device Owner değil");
                } else {
                    lastError = null;
                    updateForegroundPackage();
                    boolean protectedForeground = foregroundPackage != null
                            && protectedApps.contains(foregroundPackage);

                    if (protectedForeground) {
                        if (activeProtectedPackage == null) {
                            if (enterProtectedSession()) {
                                activeProtectedPackage = foregroundPackage;
                            }
                        } else if (!foregroundPackage.equals(activeProtectedPackage)) {
                            activeProtectedPackage = foregroundPackage;
                        }
                    } else if (activeProtectedPackage != null) {
                        exitProtectedSession();
                    }
                }

                updateNotificationForState();
            } catch (Throwable t) {
                setError(t.getClass().getSimpleName());
            }

            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Set<String> getProtectedApps() {
        android.content.SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        return new HashSet<>(p.getStringSet(MainActivity.KEY_PROTECTED, Collections.<String>emptySet()));
    }

    private boolean hasUsageAccess() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return false;
            long now = System.currentTimeMillis();
            return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000L, now) != null
                    && !usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000L, now).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void updateForegroundPackage() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (usm == null) return;

        long end = System.currentTimeMillis();
        long begin = Math.max(0L, end - EVENT_WINDOW_MS);
        UsageEvents events = usm.queryEvents(begin, end);
        if (events == null) return;

        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            long time = event.getTimeStamp();
            if (time < lastProcessedEventTime) continue;
            lastProcessedEventTime = time;

            int type = event.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED
                    || type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                foregroundPackage = event.getPackageName();
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED
                    || type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (event.getPackageName() != null
                        && event.getPackageName().equals(foregroundPackage)) {
                    foregroundPackage = null;
                }
            }
        }
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private boolean isAirplaneModeOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean setAirplaneMode(boolean enabled) {
        if (!isDeviceOwner()) {
            setError("Device Owner gerekli");
            return false;
        }

        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            setError("DevicePolicyManager yok");
            return false;
        }

        try {
            ComponentName admin = new ComponentName(this, AirlockAdminReceiver.class);
            dpm.setGlobalSetting(admin, Settings.Global.AIRPLANE_MODE_ON, enabled ? "1" : "0");

            Intent changed = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            changed.putExtra("state", enabled);
            sendBroadcast(changed);

            boolean actual = isAirplaneModeOn();
            if (actual != enabled) {
                setError("Uçak modu ayarı sistem tarafından uygulanmadı");
                return false;
            }
            return true;
        } catch (SecurityException e) {
            setError("Device Owner yetkisi reddedildi");
            return false;
        } catch (Exception e) {
            setError(e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean enterProtectedSession() {
        airplaneStateBeforeSession = isAirplaneModeOn();
        if (airplaneStateBeforeSession) {
            return true;
        }
        return setAirplaneMode(true);
    }

    private void exitProtectedSession() {
        if (activeProtectedPackage == null) return;

        if (airplaneStateBeforeSession != null && !airplaneStateBeforeSession) {
            setAirplaneMode(false);
        }

        activeProtectedPackage = null;
        airplaneStateBeforeSession = null;
    }

    private void setError(String error) {
        lastError = error;
    }

    private void updateNotificationForState() {
        String text;
        if (lastError != null) {
            text = "⚠ " + lastError;
        } else if (activeProtectedPackage != null) {
            text = "✈ Uçak modu açık: " + activeProtectedPackage;
        } else {
            text = "AirLock izliyor • uçak modu kapalı";
        }
        updateNotification(text);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AirLock", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("AirLock uygulama izleme servisi");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(com.pmdgaming.airlock.R.drawable.ic_airlock)
                    .setContentTitle("AirLock")
                    .setContentText(text)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(com.pmdgaming.airlock.R.drawable.ic_airlock)
                .setContentTitle("AirLock")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        exitProtectedSession();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
