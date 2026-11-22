package com.slayer.abyss;

import android.content.Intent;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Lets the player tell Android what's playing, and sends notification button presses back to it. */
@CapacitorPlugin(name = "AbyssMedia")
public class AbyssMediaPlugin extends Plugin {
    private static AbyssMediaPlugin self;

    @Override public void load() { self = this; }

    static void emit(String action) {
        if (self == null) return;
        JSObject o = new JSObject(); o.put("action", action);
        self.notifyListeners("action", o, true);
    }
    static void emitSeek(long ms) {
        if (self == null) return;
        JSObject o = new JSObject(); o.put("action", "seek"); o.put("position", ms);
        self.notifyListeners("action", o, true);
    }

    @PluginMethod public void update(PluginCall call) {
        Intent i = new Intent(getContext(), MediaService.class).setAction(MediaService.ACT_UPDATE)
                .putExtra("title", call.getString("title", "Abyss"))
                .putExtra("artist", call.getString("artist", ""))
                .putExtra("art", call.getString("art", ""))
                .putExtra("playing", Boolean.TRUE.equals(call.getBoolean("playing", false)))
                .putExtra("position", (long) (call.getDouble("position", 0.0) * 1000))
                .putExtra("duration", (long) (call.getDouble("duration", 0.0) * 1000));
        try {
            if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i); else getContext().startService(i);
        } catch (Exception e) { getContext().startService(i); }
        call.resolve();
    }

    @PluginMethod public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), MediaService.class));
        call.resolve();
    }
}
