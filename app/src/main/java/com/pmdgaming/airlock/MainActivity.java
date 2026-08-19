package com.pmdgaming.airlock;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends android.app.Activity {
    public static final String PREFS = "airlock_prefs";
    public static final String KEY_PROTECTED = "protected_packages";

    private final List<AppEntry> allApps = new ArrayList<>();
    private AppAdapter adapter;
    private SharedPreferences prefs;
    private TextView status;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, float size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(Color.WHITE);
        return v;
    }

    private GradientDrawable background(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        loadApps();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (!getProtectedPackages().isEmpty()) MonitorService.start(this);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(8));
        root.setBackgroundColor(Color.rgb(10, 12, 18));

        TextView title = text("✈ AirLock", 27);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView subtitle = text("Belirlediğin uygulamalar açıkken uçak modu otomatik olarak açılır.", 14);
        subtitle.setTextColor(Color.rgb(190, 196, 210));
        subtitle.setPadding(0, 0, 0, dp(10));
        root.addView(subtitle);

        status = text("Kontrol ediliyor…", 13);
        status.setPadding(dp(12), dp(9), dp(12), dp(9));
        status.setBackground(background(Color.rgb(29, 34, 46), 12));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, dp(8));

        Button usage = new Button(this);
        usage.setText("Kullanım erişimi");
        usage.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        actions.addView(usage, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button admin = new Button(this);
        admin.setText("Cihaz yöneticisi");
        admin.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
        LinearLayout.LayoutParams adminLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        adminLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(admin, adminLp);
        root.addView(actions);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Uygulama ara…");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.rgb(145, 151, 165));
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(background(Color.rgb(24, 28, 38), 14));
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setPadding(0, dp(8), 0, 0);
        adapter = new AppAdapter(this, allApps);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { adapter.getFilter().filter(s); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        setContentView(root);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        allApps.clear();
        for (ApplicationInfo info : installed) {
            if (info.packageName.equals(getPackageName())) continue;
            CharSequence label;
            try {
                label = pm.getApplicationLabel(info);
            } catch (Exception e) {
                continue;
            }
            if (label == null || label.toString().trim().isEmpty()) continue;
            allApps.add(new AppEntry(info.packageName, label.toString(), pm.getApplicationIcon(info),
                    (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0));
        }
        Collections.sort(allApps, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                if (a.system != b.system) return a.system ? 1 : -1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        adapter.notifyDataSetChanged();
    }

    public Set<String> getProtectedPackages() {
        return new HashSet<>(prefs.getStringSet(KEY_PROTECTED, Collections.<String>emptySet()));
    }

    public void setProtected(String packageName, boolean enabled) {
        Set<String> next = getProtectedPackages();
        if (enabled) next.add(packageName); else next.remove(packageName);
        prefs.edit().putStringSet(KEY_PROTECTED, next).apply();
        if (next.isEmpty()) MonitorService.stop(this); else MonitorService.start(this);
        refreshStatus();
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean owner = isDeviceOwner();
        boolean usage = hasUsageAccess();
        String text;
        if (!owner) {
            text = "⚠ Device Owner gerekli — uçak modunu değiştirme yetkisi yok.";
            status.setTextColor(Color.rgb(255, 205, 110));
        } else if (!usage) {
            text = "⚠ Usage Access gerekli — uygulamanın hangi uygulamada olduğunu göremiyor.";
            status.setTextColor(Color.rgb(255, 205, 110));
        } else {
            text = "✓ AirLock hazır • " + getProtectedPackages().size() + " uygulama korunuyor";
            status.setTextColor(Color.rgb(130, 235, 170));
        }
        status.setText(text);
    }

    public static class AppEntry {
        final String packageName;
        final String label;
        final android.graphics.drawable.Drawable icon;
        final boolean system;
        AppEntry(String packageName, String label, android.graphics.drawable.Drawable icon, boolean system) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.system = system;
        }
    }

    private class AppAdapter extends ArrayAdapter<AppEntry> implements Filterable {
        private final List<AppEntry> source;
        private final List<AppEntry> filtered;
        private final Filter filter = new Filter() {
            @Override protected FilterResults performFiltering(CharSequence constraint) {
                String q = constraint == null ? "" : constraint.toString().trim().toLowerCase();
                List<AppEntry> result = new ArrayList<>();
                for (AppEntry a : source) {
                    if (q.isEmpty() || a.label.toLowerCase().contains(q) || a.packageName.toLowerCase().contains(q)) result.add(a);
                }
                FilterResults fr = new FilterResults();
                fr.values = result;
                fr.count = result.size();
                return fr;
            }
            @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                filtered.clear();
                //noinspection unchecked
                filtered.addAll((List<AppEntry>) results.values);
                notifyDataSetChanged();
            }
        };

        AppAdapter(Context context, List<AppEntry> data) {
            super(context, android.R.layout.simple_list_item_1, data);
            source = data;
            filtered = new ArrayList<>(data);
        }

        @Override public int getCount() { return filtered.size(); }
        @Override public AppEntry getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public Filter getFilter() { return filter; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry app = getItem(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(8), dp(8));
            row.setBackground(background(Color.rgb(19, 23, 32), 16));

            ImageView icon = new ImageView(MainActivity.this);
            icon.setImageDrawable(app.icon);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(50), dp(50));
            iconLp.setMargins(0, 0, dp(12), 0);
            row.addView(icon, iconLp);

            LinearLayout names = new LinearLayout(MainActivity.this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(app.label, 16);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            names.addView(name);
            TextView pkg = text(app.packageName, 11);
            pkg.setTextColor(Color.rgb(135, 143, 160));
            names.addView(pkg);
            row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));

            Switch sw = new Switch(MainActivity.this);
            sw.setText(app.system ? "Sistem" : "Koruyucu");
            sw.setTextSize(11);
            sw.setTextColor(Color.rgb(180, 188, 202));
            sw.setGravity(Gravity.CENTER);
            sw.setChecked(getProtectedPackages().contains(app.packageName));
            sw.setOnCheckedChangeListener(null);
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> setProtected(app.packageName, isChecked));
            row.addView(sw, new LinearLayout.LayoutParams(dp(94), dp(58)));
            return row;
        }
    }
}
