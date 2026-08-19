package com.pmdgaming.airlock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class AirLockAccessibilityService extends AccessibilityService {
    private static final String CHANNEL_ID = "airlock_accessibility";
    private static final int NOTIFICATION_ID = 4251;
    private static final long TOGGLE_DELAY_MS = 250L;
    private static final int MAX_TOGGLE_ATTEMPTS = 4;

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
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
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
        if (packageName.equals("com.android.systemui")) return;

        Set<String> protectedApps = getProtectedApps();
        if (protectedApps.isEmpty()) {
            if (activeProtectedPackage != null) exitProtectedSession();
            return;
        }

        if (protectedApps.contains(packageName)) {
            if (activeProtectedPackage == null || !activeProtectedPackage.equals(packageName)) {
                enterProtectedSession(packageName);
            }
        } else if (activeProtectedPackage != null && !suppressSystemUiExit) {
            exitProtectedSession();
        }
    }

    @Override
    public void onInterrupt() {
        // Keep state intact; Android may briefly interrupt accessibility events.
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
        String packageName = activeProtectedPackage;
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

    private void toggleAirplaneThroughQuickSettings(boolean enabled) {
        if (automationRunning.getAndSet(true)) return;
        suppressSystemUiExit = true;
        showNotification(enabled ? "✈ Uçak modu açılıyor…" : "Uçak modu kapatılıyor…");
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) {
            automationRunning.set(false);
            suppressSystemUiExit = false;
            showNotification("⚠ Quick Settings açılamadı");
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
        String[] tokens = {"airplane", "flight mode", "uçak", "uçuş modu", "uçak modu"};
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(java.util.Locale.ROOT);
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT);
            String resource = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(java.util.Locale.ROOT);
            boolean match = false;
            for (String token : tokens) {
                if (text.contains(token) || desc.contains(token) || resource.contains(token.replace(" ", "_"))) {
                    match = true;
                    break;
                }
            }
            if (match && (node.isClickable() || node.isCheckable())) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private void verifyAirplaneState(boolean enabled) {
        boolean actual = isAirplaneModeOn();
        if (actual != enabled) {
            automationRunning.set(false);
            suppressSystemUiExit = false;
            if (++toggleAttempts < MAX_TOGGLE_ATTEMPTS) {
                handler.postDelayed(() -> toggleAirplaneThroughQuickSettings(enabled), TOGGLE_DELAY_MS);
                return;
            }
            showNotification("⚠ Uçak modu değiştirilemedi");
            if (!enabled) activeProtectedPackage = null;
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
        if (activeProtectedPackage != null) exitProtectedSession();
        super.onDestroy();
    }
}
