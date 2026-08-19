package com.pmdgaming.airlock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class AirLockAccessibilityService extends AccessibilityService {
    private static final String CHANNEL_ID = "airlock_accessibility";
    private static final int NOTIFICATION_ID = 4251;
    private static final long TOGGLE_DELAY_MS = 350L;
    private static final int MAX_TOGGLE_ATTEMPTS = 6;
    private static final String SYSTEM_UI = "com.android.systemui";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean automationRunning = new AtomicBoolean(false);
    private String activeProtectedPackage;
    private Boolean airplaneBeforeSession;
    private int toggleAttempts;
    private boolean suppressSystemUiExit;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 75;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        createNotificationChannel();
        showNotification("AirLock izleme aktif");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();

        if (SYSTEM_UI.equals(packageName)) {
            if (automationRunning.get()) {
                handler.post(() -> findAndClickAirplaneTile(isAirplaneTargetOn()));
            }
            return;
        }

        if (automationRunning.get() || suppressSystemUiExit) return;

        Set<String> protectedApps = getProtectedApps();
        if (protectedApps.isEmpty()) {
            if (activeProtectedPackage != null) exitProtectedSession();
            return;
        }

        if (protectedApps.contains(packageName)) {
            if (activeProtectedPackage == null || !activeProtectedPackage.equals(packageName)) {
                enterProtectedSession(packageName);
            }
        } else if (activeProtectedPackage != null) {
            exitProtectedSession();
        }
    }

    @Override
    public void onInterrupt() {
        // Keep the current session state; transient accessibility interruptions are normal.
    }

    private Set<String> getProtectedApps() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(MainActivity.KEY_PROTECTED, Collections.<String>emptySet()));
    }

    private void enterProtectedSession(String packageName) {
        activeProtectedPackage = packageName;
        airplaneBeforeSession = isAirplaneModeOn();
        if (Boolean.TRUE.equals(airplaneBeforeSession)) {
            showNotification("✈ Uçak modu zaten açık: " + packageName);
            return;
        }
        toggleAttempts = 0;
        toggleAirplaneThroughQuickSettings(true);
    }

    private void exitProtectedSession() {
        activeProtectedPackage = null;
        if (airplaneBeforeSession == null) return;

        boolean restoreOff = !airplaneBeforeSession;
        airplaneBeforeSession = null;
        if (restoreOff && isAirplaneModeOn()) {
            toggleAttempts = 0;
            toggleAirplaneThroughQuickSettings(false);
        } else {
            showNotification("AirLock izliyor • uçak modu kapalı");
        }
    }

    private boolean isAirplaneModeOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isAirplaneTargetOn() {
        if (airplaneBeforeSession != null && activeProtectedPackage != null) {
            return !Boolean.TRUE.equals(airplaneBeforeSession);
        }
        return isAirplaneModeOn();
    }

    private void toggleAirplaneThroughQuickSettings(boolean enabled) {
        if (!automationRunning.compareAndSet(false, true)) return;
        suppressSystemUiExit = true;
        toggleAttempts = 0;
        showNotification(enabled ? "✈ Uçak modu açılıyor…" : "✈ Uçak modu kapatılıyor…");
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) {
            finishAutomation("⚠ Quick Settings açılamadı");
            return;
        }
        handler.postDelayed(() -> findAndClickAirplaneTile(enabled), TOGGLE_DELAY_MS);
    }

    private void findAndClickAirplaneTile(boolean enabled) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo target = findAirplaneNode(root);
        if (target == null) {
            if (++toggleAttempts < MAX_TOGGLE_ATTEMPTS) {
                handler.postDelayed(() -> findAndClickAirplaneTile(enabled), TOGGLE_DELAY_MS);
                return;
            }
            finishAutomation("⚠ Uçak modu kutusu bulunamadı");
            return;
        }

        boolean checked = target.isChecked();
        if (checked != enabled) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        target.recycle();
        handler.postDelayed(() -> verifyAirplaneState(enabled), TOGGLE_DELAY_MS);
    }

    private AccessibilityNodeInfo findAirplaneNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(Locale.ROOT);
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase(Locale.ROOT);
            String resource = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(Locale.ROOT);
            boolean match = containsAirplaneToken(text) || containsAirplaneToken(desc) || containsAirplaneToken(resource);
            if (match && (node.isClickable() || node.isCheckable())) return node;

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private boolean containsAirplaneToken(String value) {
        return value.contains("airplane")
                || value.contains("flight mode")
                || value.contains("airplanemode")
                || value.contains("airplane_mode")
                || value.contains("uçak")
                || value.contains("uçuş modu")
                || value.contains("uçak modu");
    }

    private void verifyAirplaneState(boolean enabled) {
        boolean actual = isAirplaneModeOn();
        if (actual != enabled) {
            if (++toggleAttempts < MAX_TOGGLE_ATTEMPTS) {
                handler.postDelayed(() -> findAndClickAirplaneTile(enabled), TOGGLE_DELAY_MS);
                return;
            }
            finishAutomation("⚠ Uçak modu değiştirilemedi");
            return;
        }
        finishAutomation(enabled ? "✈ Uçak modu açık" : "AirLock izliyor • uçak modu kapalı");
    }

    private void finishAutomation(String message) {
        automationRunning.set(false);
        suppressSystemUiExit = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
        showNotification(message);
    }

    private void showNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Intent open = new Intent(this, MainActivity.class);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, open, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(com.pmdgaming.airlock.R.drawable.ic_airlock)
                .setContentTitle("AirLock")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pendingIntent);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "AirLock erişilebilirlik", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("AirLock otomasyon durumu");
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        activeProtectedPackage = null;
        airplaneBeforeSession = null;
        automationRunning.set(false);
        suppressSystemUiExit = false;
        super.onDestroy();
    }
}
