package com.slayer.abyss;

import android.content.Intent;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;

/** Lets the player page drive the native Android player (PlaybackService). Everything here runs on the main thread. */
@CapacitorPlugin(name = "AbyssPlayer")
public class AbyssPlayerPlugin extends Plugin {
    private static AbyssPlayerPlugin self;
    private static final ArrayList<Runnable> waiting = new ArrayList<>();
    private static final Runnable ticker = new Runnable() { @Override public void run() {
        emitState();
        ExoPlayer p = player();
        if (p != null && p.isPlaying()) PlaybackService.main.postDelayed(this, 500);
    }};

    @Override public void load() { self = this; }

    static ExoPlayer player() { PlaybackService s = PlaybackService.instance; return s != null ? s.player : null; }

    static void serviceReady() { for (Runnable r : waiting) r.run(); waiting.clear(); }

    /** Run once the service and its player exist, starting the service if needed. */
    interface PlayerJob { void run(ExoPlayer p); }
    private void withPlayer(final PluginCall call, final PlayerJob fn) {
        PlaybackService.main.post(() -> {
            ExoPlayer p = player();
            if (p != null) { try { fn.run(p); call.resolve(); } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); } return; }
            waiting.add(() -> { ExoPlayer q = player(); if (q == null) { call.reject("player not ready"); return; }
                try { fn.run(q); call.resolve(); } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); } });
            try { getContext().startService(new Intent(getContext(), PlaybackService.class)); }
            catch (Exception e) { call.reject("could not start the player: " + e.getMessage()); }
        });
    }

    private static MediaItem item(String url, String title, String artist, String album, String art) {
        MediaMetadata.Builder md = new MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album);
        if (art != null && art.startsWith("http")) md.setArtworkUri(Uri.parse(art));
        return new MediaItem.Builder().setMediaId(url).setUri(url).setMediaMetadata(md.build()).build();
    }

    @PluginMethod public void load(PluginCall call) {
        final String url = call.getString("url", "");
        final MediaItem it = item(url, call.getString("title", "Abyss"), call.getString("artist", ""), call.getString("album", ""), call.getString("art", ""));
        final double at = call.getDouble("position", 0.0);
        final boolean autoplay = Boolean.TRUE.equals(call.getBoolean("autoplay", false));
        withPlayer(call, p -> {
            p.setMediaItem(it, (long) (at * 1000));
            p.prepare();
            p.setPlayWhenReady(autoplay);
            emitState();
        });
    }

    /** Queue the song that comes next, so it starts by itself even if the page is asleep. */
    @PluginMethod public void setNext(PluginCall call) {
        final String url = call.getString("url", "");
        final MediaItem it = url.isEmpty() ? null : item(url, call.getString("title", ""), call.getString("artist", ""), call.getString("album", ""), call.getString("art", ""));
        withPlayer(call, p -> {
            while (p.getMediaItemCount() > p.getCurrentMediaItemIndex() + 1) p.removeMediaItem(p.getMediaItemCount() - 1);
            if (it != null && p.getMediaItemCount() > 0) p.addMediaItem(it);
        });
    }

    @PluginMethod public void updateInfo(PluginCall call) {
        final String title = call.getString("title", "Abyss"), artist = call.getString("artist", ""), album = call.getString("album", ""), art = call.getString("art", "");
        withPlayer(call, p -> {
            MediaItem cur = p.getCurrentMediaItem();
            if (cur == null) return;
            MediaItem upd = item(cur.mediaId, title, artist, album, art);
            p.replaceMediaItem(p.getCurrentMediaItemIndex(), upd);
        });
    }

    @PluginMethod public void setButtons(PluginCall call) {
        final boolean like = Boolean.TRUE.equals(call.getBoolean("liked", false)), shuf = Boolean.TRUE.equals(call.getBoolean("shuffle", false));
        withPlayer(call, p -> { PlaybackService s = PlaybackService.instance; if (s != null) s.setButtons(like, shuf); });
    }

    @PluginMethod public void play(PluginCall call) { withPlayer(call, p -> { if (p.getPlaybackState() == Player.STATE_ENDED) p.seekTo(0); p.play(); }); }
    @PluginMethod public void pause(PluginCall call) { withPlayer(call, p -> p.pause()); }
    @PluginMethod public void seek(PluginCall call) { final double s = call.getDouble("position", 0.0); withPlayer(call, p -> { p.seekTo((long) (s * 1000)); emitEvent("seeked"); }); }
    @PluginMethod public void setVolume(PluginCall call) { final double v = call.getDouble("volume", 1.0); withPlayer(call, p -> p.setVolume((float) Math.max(0, Math.min(1, v)))); }
    @PluginMethod public void setRate(PluginCall call) { final double r = call.getDouble("rate", 1.0); withPlayer(call, p -> p.setPlaybackParameters(new PlaybackParameters((float) r))); }
    @PluginMethod public void stop(PluginCall call) { withPlayer(call, p -> { p.stop(); p.clearMediaItems(); }); }

    static void emitState() {
        if (self == null) return;
        ExoPlayer p = player();
        JSObject o = new JSObject(); o.put("type", "state");
        if (p != null) {
            o.put("playing", p.isPlaying());
            o.put("wants", p.getPlayWhenReady());
            o.put("position", p.getCurrentPosition() / 1000.0);
            long d = p.getDuration();
            o.put("duration", d > 0 ? d / 1000.0 : 0);
            o.put("buffering", p.getPlaybackState() == Player.STATE_BUFFERING);
            if (p.isPlaying()) { PlaybackService.main.removeCallbacks(ticker); PlaybackService.main.postDelayed(ticker, 500); }
        }
        self.notifyListeners("player", o, false);
    }
    static void emitEvent(String name) { if (self == null) return; JSObject o = new JSObject(); o.put("type", name); self.notifyListeners("player", o, true); }
    static void emitAction(String a) { if (self == null) return; JSObject o = new JSObject(); o.put("type", "action"); o.put("action", a); self.notifyListeners("player", o, true); }
    static void emitAdvanced(String url) { if (self == null) return; JSObject o = new JSObject(); o.put("type", "advanced"); o.put("url", url); self.notifyListeners("player", o, true); }
    static void emitError(String m) { if (self == null) return; JSObject o = new JSObject(); o.put("type", "error"); o.put("message", m); self.notifyListeners("player", o, true); }
}
