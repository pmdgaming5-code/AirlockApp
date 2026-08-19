package com.pmdgaming.airlock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
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
import java.util.Locale;
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
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(8));
        root.setBackgroundColor(Color.rgb(10, 12, 18));

        TextView title = text("✈ AirLock", 27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView subtitle = text("Seçtiğin uygulama öne geldiğinde uçak modu açılır; başka uygulamaya geçtiğinde önceki duruma döner.", 14);
        subtitle.setTextColor(Color.rgb(190, 196, 210));
        subtitle.setPadding(0, 0, 0, dp(10));
        root.addView(subtitle);

        status = text("Kontrol ediliyor…", 13);
        status.setPadding(dp(12), dp(9), dp(12), dp(9));
        status.setBackground(background(Color.rgb(29, 34, 46), 12));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, dp(8));

        Button accessibility = new Button(this);
        accessibility.setText("Erişilebilirliği aç");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        actions.addView(accessibility, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button appSettings = new Button(this);
        appSettings.setText("AirLock ayarları");
        appSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        settingsLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(appSettings, settingsLp);
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

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { adapter.getFilter().filter(s); }
            @Override public void afterTextChanged(Editable s) {}
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
            Drawable icon;
            try {
                label = pm.getApplicationLabel(info);
                icon = pm.getApplicationIcon(info);
            } catch (Exception e) {
                continue;
            }
            if (label == null || label.toString().trim().isEmpty()) continue;
            allApps.add(new AppEntry(info.packageName, label.toString(), icon,
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
        refreshStatus();
    }

    private boolean isAccessibilityEnabled() {
        try {
            AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (manager == null) return false;
            for (AccessibilityServiceInfo info :
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getResolveInfo() != null
                        && info.getResolveInfo().serviceInfo != null
                        && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean enabled = isAccessibilityEnabled();
        int count = getProtectedPackages().size();
        if (!enabled) {
            status.setText("⚠ Erişilebilirlik hizmetini aç • " + count + " uygulama seçili");
            status.setTextColor(Color.rgb(255, 205, 110));
        } else {
            status.setText("✓ AirLock aktif • " + count + " uygulama korunuyor");
            status.setTextColor(Color.rgb(130, 235, 170));
        }
    }

    public static class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        final boolean system;

        AppEntry(String packageName, String label, Drawable icon, boolean system) {
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
                String q = constraint == null ? "" : constraint.toString().trim().toLowerCase(Locale.ROOT);
                List<AppEntry> result = new ArrayList<>();
                for (AppEntry a : source) {
                    if (q.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(q)
                            || a.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                        result.add(a);
                    }
                }
                FilterResults fr = new FilterResults();
                fr.values = result;
                fr.count = result.size();
                return fr;
            }

            @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                filtered.clear();
                if (results != null && results.values instanceof List) {
                    //noinspection unchecked
                    filtered.addAll((List<AppEntry>) results.values);
                }
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
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
            sw.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
                    setProtected(app.packageName, isChecked));
            row.addView(sw, new LinearLayout.LayoutParams(dp(94), dp(58)));
            return row;
        }
    }
}
