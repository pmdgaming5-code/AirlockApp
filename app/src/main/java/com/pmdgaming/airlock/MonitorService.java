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
    private static final long POLL_MS = 700L;

    private volatile boolean running;
    private Thread worker;
    private String activeProtectedPackage = null;

    public static void start(Context context) {
        Intent intent = new Intent(context, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("AirLock izleme aktif"));
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
                    turnAirplaneOff();
                    stopSelf();
                    return;
                }

                String foreground = getForegroundPackage();
                boolean protectedForeground = foreground != null && protectedApps.contains(foreground);

                if (protectedForeground && !foreground.equals(activeProtectedPackage)) {
                    if (turnAirplane(true)) {
                        activeProtectedPackage = foreground;
                        updateNotification("✈ Uçak modu açık: " + foreground);
                    }
                } else if (!protectedForeground && activeProtectedPackage != null) {
                    if (turnAirplane(false)) {
                        activeProtectedPackage = null;
                        updateNotification("AirLock izliyor • uçak modu kapalı");
                    }
                }
            } catch (Throwable ignored) {
                // Keep the monitoring loop alive even if a vendor-specific API fails.
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

    private String getForegroundPackage() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (usm == null) return null;
        long end = System.currentTimeMillis();
        long begin = end - 5_000L;
        UsageEvents events = usm.queryEvents(begin, end);
        if (events == null) return null;

        UsageEvents.Event event = new UsageEvents.Event();
        String latestPackage = null;
        long latestTime = 0L;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.getTimeStamp() >= latestTime) {
                    latestTime = event.getTimeStamp();
                    latestPackage = event.getPackageName();
                }
            }
        }
        return latestPackage;
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private boolean turnAirplane(boolean enabled) {
        if (!isDeviceOwner()) return false;
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm == null) return false;
        try {
            ComponentName admin = new ComponentName(this, AirlockAdminReceiver.class);
            dpm.setGlobalSetting(admin, Settings.Global.AIRPLANE_MODE_ON, enabled ? "1" : "0");

            Intent changed = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            changed.putExtra("state", enabled);
            sendBroadcast(changed);
            return true;
        } catch (SecurityException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void turnAirplaneOff() {
        if (activeProtectedPackage != null) {
            turnAirplane(false);
            activeProtectedPackage = null;
        }
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
        turnAirplaneOff();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
