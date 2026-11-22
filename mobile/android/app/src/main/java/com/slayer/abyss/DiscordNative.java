package com.slayer.abyss;

/** Thin wrapper over the native Discord bridge. available is false when the app was built without the Discord SDK. */
final class DiscordNative {
    static boolean available = false;
    static {
        try { System.loadLibrary("discord_partner_sdk"); System.loadLibrary("abyssdiscord"); available = true; }
        catch (Throwable t) { available = false; }
    }
    static native void init(long appId);
    static native void tick();
    static native String poll();
    static native void setActivity(String details, String state, String largeImage, String largeText,
                                   String smallImage, String smallText, long start, long end, String btnLabel, String btnUrl);
    static native void clear();
    static native void authorize();
    static native void refresh(String refreshToken);
    static native void disconnect();
}
