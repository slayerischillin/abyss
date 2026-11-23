package com.slayer.abyss;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.CommandButton;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import android.os.Bundle;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * The real music player on Android. It runs as a media service, so playback keeps going
 * on the home screen and with the screen off, and Android shows the notification, lock screen
 * controls and headphone-button support for it.
 */
public class PlaybackService extends MediaSessionService {
    static PlaybackService instance;
    static final Handler main = new Handler(Looper.getMainLooper());

    ExoPlayer player;
    private MediaSession session;

    @Override public void onCreate() {
        super.onCreate();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android) Abyss")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)   // pause when headphones are unplugged
                .setWakeMode(C.WAKE_MODE_NETWORK)    // keep the CPU and Wi-Fi awake while streaming
                .build();

        // Lock screen / notification previous and next go back to the player page when there is nothing queued here.
        Player wrapped = new ForwardingPlayer(player) {
            @Override public Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon().remove(COMMAND_SET_SHUFFLE_MODE).remove(COMMAND_SET_REPEAT_MODE)
                        .add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(COMMAND_SEEK_TO_PREVIOUS).add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build();
            }
            @Override public boolean isCommandAvailable(int c) {
                if (c == COMMAND_SET_SHUFFLE_MODE || c == COMMAND_SET_REPEAT_MODE) return false;
                return c == COMMAND_SEEK_TO_NEXT || c == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || c == COMMAND_SEEK_TO_PREVIOUS
                        || c == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM || super.isCommandAvailable(c);
            }
            @Override public void seekToNext() { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); else AbyssPlayerPlugin.emitAction("next"); }
            @Override public void seekToNextMediaItem() { seekToNext(); }
            @Override public void seekToPrevious() { AbyssPlayerPlugin.emitAction("prev"); }
            @Override public void seekToPreviousMediaItem() { seekToPrevious(); }
        };

        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, wrapped).setSessionActivity(pi).setCallback(new MediaSession.Callback() {
            @Override public MediaSession.ConnectionResult onConnect(MediaSession s, MediaSession.ControllerInfo c) {
                SessionCommands sc = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(LIKE).add(SHUF).remove(SessionCommand.COMMAND_CODE_SESSION_SET_RATING).build();
                return new MediaSession.ConnectionResult.AcceptedResultBuilder(s).setAvailableSessionCommands(sc).setCustomLayout(layout()).build();
            }
            @Override public ListenableFuture<SessionResult> onCustomCommand(MediaSession s, MediaSession.ControllerInfo c, SessionCommand cmd, Bundle args) {
                if (LIKE.customAction.equals(cmd.customAction)) { liked = !liked; AbyssPlayerPlugin.emitAction("like"); }
                else if (SHUF.customAction.equals(cmd.customAction)) { shuffled = !shuffled; AbyssPlayerPlugin.emitAction("shuffle"); }
                refreshButtons();
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            }
        }).build();

        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) { AbyssPlayerPlugin.emitState(); }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) AbyssPlayerPlugin.emitEvent("ended");
                AbyssPlayerPlugin.emitState();
            }
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    // finished a song and moved straight into the one queued after it
                    while (player.getCurrentMediaItemIndex() > 0) player.removeMediaItem(0);
                    AbyssPlayerPlugin.emitAdvanced(item != null ? item.mediaId : "");
                }
            }
            @Override public void onPlayerError(PlaybackException error) { AbyssPlayerPlugin.emitError(error.getErrorCodeName() + ": " + error.getMessage()); }
        });
        instance = this;
        AbyssPlayerPlugin.serviceReady();
    }

    // Notification / lock screen buttons: shuffle and like, kept in step with the app
    static final SessionCommand LIKE = new SessionCommand("abyss.LIKE", Bundle.EMPTY);
    static final SessionCommand SHUF = new SessionCommand("abyss.SHUFFLE", Bundle.EMPTY);
    boolean liked = false, shuffled = false;
    ImmutableList<CommandButton> layout() {
        CommandButton sh = new CommandButton.Builder().setDisplayName(shuffled ? "Shuffle on" : "Shuffle off")
                .setIconResId(shuffled ? R.drawable.ab_shuffle : R.drawable.ab_shuffle_off).setSessionCommand(SHUF).build();
        CommandButton lk = new CommandButton.Builder().setDisplayName(liked ? "Remove from Liked" : "Like")
                .setIconResId(liked ? R.drawable.ab_heart : R.drawable.ab_heart_outline).setSessionCommand(LIKE).build();
        return ImmutableList.of(sh, lk);
    }
    void refreshButtons() { if (session != null) session.setCustomLayout(layout()); }
    void setButtons(boolean like, boolean shuf) { liked = like; shuffled = shuf; refreshButtons(); }

    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }

    // Swiping Abyss away from recents stops the music only when it's paused.
    @Override public void onTaskRemoved(@Nullable Intent rootIntent) {
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) stopSelf();
    }

    @Override public void onDestroy() {
        instance = null;
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
