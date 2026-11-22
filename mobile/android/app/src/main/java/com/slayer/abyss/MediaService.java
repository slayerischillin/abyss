package com.slayer.abyss;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Keeps Abyss alive while music plays, and shows the now-playing notification and lock screen controls. */
public class MediaService extends Service {
    static final String CHANNEL = "abyss_playback";
    static final int ID = 999;
    static final String ACT_PLAY = "com.slayer.abyss.PLAY", ACT_PAUSE = "com.slayer.abyss.PAUSE",
            ACT_NEXT = "com.slayer.abyss.NEXT", ACT_PREV = "com.slayer.abyss.PREV", ACT_UPDATE = "com.slayer.abyss.UPDATE";

    private MediaSessionCompat session;
    private PowerManager.WakeLock wake;
    private String title = "Abyss", artist = "", artUrl = "", loadedArt = "";
    private boolean playing = false;
    private long position = 0, duration = 0;
    private Bitmap art;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        session = new MediaSessionCompat(this, "Abyss");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { AbyssMediaPlugin.emit("play"); }
            @Override public void onPause() { AbyssMediaPlugin.emit("pause"); }
            @Override public void onSkipToNext() { AbyssMediaPlugin.emit("next"); }
            @Override public void onSkipToPrevious() { AbyssMediaPlugin.emit("prev"); }
            @Override public void onSeekTo(long pos) { AbyssMediaPlugin.emitSeek(pos); }
        });
        session.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Abyss:playback");
        wake.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String a = i != null ? i.getAction() : null;
        if (ACT_PLAY.equals(a)) AbyssMediaPlugin.emit("play");
        else if (ACT_PAUSE.equals(a)) AbyssMediaPlugin.emit("pause");
        else if (ACT_NEXT.equals(a)) AbyssMediaPlugin.emit("next");
        else if (ACT_PREV.equals(a)) AbyssMediaPlugin.emit("prev");
        else if (ACT_UPDATE.equals(a) && i != null) {
            title = val(i.getStringExtra("title"), "Abyss");
            artist = val(i.getStringExtra("artist"), "");
            playing = i.getBooleanExtra("playing", false);
            position = i.getLongExtra("position", 0);
            duration = i.getLongExtra("duration", 0);
            String u = val(i.getStringExtra("art"), "");
            if (!u.equals(artUrl)) { artUrl = u; art = null; loadArt(u); }
        }
        refresh();
        return START_NOT_STICKY;
    }

    private static String val(String s, String d) { return s == null || s.isEmpty() ? d : s; }

    private void loadArt(final String u) {
        if (!u.startsWith("http")) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 Abyss");
                InputStream in = c.getInputStream();
                Bitmap b = BitmapFactory.decodeStream(in);
                in.close();
                if (b != null && u.equals(artUrl)) { art = Bitmap.createScaledBitmap(b, 512, 512 * b.getHeight() / Math.max(1, b.getWidth()), true); loadedArt = u; refresh(); }
            } catch (Exception ignored) {}
        }).start();
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void refresh() {
        MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        if (art != null) md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, position, playing ? 1f : 0f)
                .build());

        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(art)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Previous", pi(ACT_PREV, 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", pi(playing ? ACT_PAUSE : ACT_PLAY, 2))
                .addAction(android.R.drawable.ic_media_next, "Next", pi(ACT_NEXT, 3))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(ID, n);
        if (playing) { if (!wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L); }
        else {
            if (wake.isHeld()) wake.release();
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH); else stopForeground(false);
        }
    }

    @Override public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
