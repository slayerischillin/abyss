package com.slayer.abyss;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Discord status for Abyss on Android, through the Discord Social SDK. */
@CapacitorPlugin(name = "AbyssDiscord")
public class AbyssDiscordPlugin extends Plugin {
    private final Handler loop = new Handler(Looper.getMainLooper());
    private boolean started = false, linked = false;
    private String status = "off", lastError = "";

    private SharedPreferences prefs() { return getContext().getSharedPreferences("abyss_discord", 0); }

    private void ensure(long appId) {
        if (started || !DiscordNative.available) return;
        try {
            Activity a = getActivity();
            Class.forName("com.discord.socialsdk.DiscordSocialSdkInit").getMethod("setEngineActivity", Activity.class).invoke(null, a);
        } catch (Throwable ignored) {}
        DiscordNative.init(appId);
        started = true;
        String refresh = prefs().getString("refresh", "");
        if (!refresh.isEmpty()) DiscordNative.refresh(refresh);
        loop.post(new Runnable() { @Override public void run() {
            try { DiscordNative.tick(); String e; int n = 0; while (n++ < 20 && (e = DiscordNative.poll()) != null) handle(e); } catch (Throwable ignored) {}
            loop.postDelayed(this, 100);
        }});
    }

    private void handle(String e) {
        if (e.startsWith("token:")) {
            String[] p = e.substring(6).split("\\|", -1);
            if (p.length >= 2 && !p[1].isEmpty()) prefs().edit().putString("refresh", p[1]).apply();
            return;
        }
        if (e.equals("linked")) { linked = true; status = "linked"; }
        else if (e.startsWith("status:")) { if ("3".equals(e.substring(7))) status = "connected"; }
        else if (e.equals("presence:ok")) { status = linked ? "showing-linked" : "showing"; lastError = ""; }
        else if (e.startsWith("error:")) {
            lastError = e.substring(6);
            if (lastError.startsWith("auth:") || lastError.startsWith("token:")) prefs().edit().remove("refresh").apply();
            status = "error";
        }
        JSObject o = new JSObject(); o.put("event", e); o.put("status", status); o.put("error", lastError); o.put("linked", linked);
        notifyListeners("status", o, true);
    }

    @PluginMethod public void info(PluginCall call) {
        JSObject o = new JSObject();
        o.put("available", DiscordNative.available); o.put("status", status); o.put("error", lastError);
        o.put("linked", linked); o.put("saved", !prefs().getString("refresh", "").isEmpty());
        call.resolve(o);
    }

    @PluginMethod public void setActivity(PluginCall call) {
        if (!DiscordNative.available) { call.resolve(); return; }
        ensure(Long.parseLong(call.getString("appId", "0")));
        DiscordNative.setActivity(call.getString("details", ""), call.getString("state", ""),
                call.getString("largeImage", ""), call.getString("largeText", ""),
                call.getString("smallImage", ""), call.getString("smallText", ""),
                (long) (double) call.getDouble("start", 0.0), (long) (double) call.getDouble("end", 0.0),
                call.getString("buttonLabel", ""), call.getString("buttonUrl", ""));
        call.resolve();
    }

    @PluginMethod public void clear(PluginCall call) { if (DiscordNative.available && started) DiscordNative.clear(); call.resolve(); }

    @PluginMethod public void connect(PluginCall call) {
        if (!DiscordNative.available) { call.reject("This build has no Discord support"); return; }
        ensure(Long.parseLong(call.getString("appId", "0")));
        DiscordNative.authorize();
        call.resolve();
    }

    @PluginMethod public void disconnect(PluginCall call) {
        prefs().edit().remove("refresh").apply(); linked = false; status = "off";
        if (DiscordNative.available && started) DiscordNative.disconnect();
        call.resolve();
    }
}
