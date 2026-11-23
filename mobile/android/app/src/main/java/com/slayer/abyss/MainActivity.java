package com.slayer.abyss;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AbyssPlayerPlugin.class);
        registerPlugin(AbyssDiscordPlugin.class);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        // Ask once to be left out of battery saving, so phones like Samsung and Xiaomi don't stop the music in the background
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            android.content.SharedPreferences sp = getSharedPreferences("abyss", MODE_PRIVATE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()) && !sp.getBoolean("askedBattery", false)) {
                sp.edit().putBoolean("askedBattery", true).apply();
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {}
        WebView wv = getBridge().getWebView();
        WebSettings ws = wv.getSettings();
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDomStorageEnabled(true);
        wv.setBackgroundColor(0xFF020104);
    }

    // Keep the music playing when the app goes to the background or the screen turns off.
    @Override
    public void onPause() {
        super.onPause();
        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv != null) { wv.onResume(); wv.resumeTimers(); }
    }

    @Override
    public void onStop() {
        super.onStop();
        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv != null) { wv.onResume(); wv.resumeTimers(); }
    }
}
