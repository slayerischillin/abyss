package com.slayer.abyss;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AbyssMediaPlugin.class);
        registerPlugin(AbyssDiscordPlugin.class);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
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
