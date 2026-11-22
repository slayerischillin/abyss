// Abyss <-> Discord Social SDK bridge.
// Java calls these through com.slayer.abyss.DiscordNative. Results come back as text events
// that Java collects with poll(), so no calls into Java from SDK threads are needed.
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"
#include <jni.h>
#include <android/log.h>
#include <deque>
#include <memory>
#include <mutex>
#include <string>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "AbyssDiscord", __VA_ARGS__)

static std::shared_ptr<discordpp::Client> g_client;
static uint64_t g_appId = 0;
static std::string g_verifier;
static std::deque<std::string> g_events;
static std::mutex g_mu;

static void push(const std::string& e) { std::lock_guard<std::mutex> l(g_mu); g_events.push_back(e); }
static std::string str(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}
static std::optional<std::string> opt(const std::string& s) { if (s.empty()) return std::nullopt; return s; }

static void useToken(const std::string& access) {
    if (!g_client) return;
    g_client->UpdateToken(discordpp::AuthorizationTokenType::Bearer, access, [](discordpp::ClientResult r) {
        if (r.Successful()) { push("linked"); g_client->Connect(); }
        else push("error:token:" + r.Error());
    });
}
static void onTokens(discordpp::ClientResult r, std::string access, std::string refresh, int32_t expires) {
    if (!r.Successful()) { push("error:auth:" + r.Error()); return; }
    push("token:" + access + "|" + refresh + "|" + std::to_string(expires));
    useToken(access);
}

extern "C" {

JNIEXPORT void JNICALL Java_com_slayer_abyss_DiscordNative_init(JNIEnv*, jclass, jlong appId) {
    if (g_client) return;
    g_appId = (uint64_t)appId;
    g_client = std::make_shared<discordpp::Client>();
    g_client->SetApplicationId(g_appId);
    g_client->SetStatusChangedCallback([](discordpp::Client::Status s, discordpp::Client::Error, int32_t) {
        push("status:" + std::to_string((int)s));
    });
    push("ready");
}

JNIEXPORT void JNICALL Java_com_slayer_abyss_DiscordNative_tick(JNIEnv*, jclass) { discordpp::RunCallbacks(); }

JNIEXPORT jstring JNICALL Java_com_slayer_abyss_DiscordNative_poll(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> l(g_mu);
    if (g_events.empty()) return nullptr;
    std::string e = g_events.front(); g_events.pop_front();
    return env->NewStringUTF(e.c_str());
}

JNIEXPORT void JNICALL Java_com_slayer_abyss_DiscordNative_setActivity(JNIEnv* env, jclass,
        jstring details, jstring state, jstring largeImage, jstring largeText,
        jstring smallImage, jstring smallText, jlong start, jlong end, jstring btnLabel, jstring btnUrl) {
    if (!g_client) return;
    discordpp::Activity a;
    a.SetType(discordpp::ActivityTypes::Listening);
    a.SetStatusDisplayType(discordpp::StatusDisplayTypes::Details);
    a.SetDetails(opt(str(env, details)));
    a.SetState(opt(str(env, state)));
    discordpp::ActivityAssets as;
    as.SetLargeImage(opt(str(env, largeImage)));
    as.SetLargeText(opt(str(env, largeText)));
    as.SetSmallImage(opt(str(env, smallImage)));
    as.SetSmallText(opt(str(env, smallText)));
    a.SetAssets(as);
    if (start > 0) {
        discordpp::ActivityTimestamps ts;
        ts.SetStart((uint64_t)start);
        if (end > start) ts.SetEnd((uint64_t)end);
        a.SetTimestamps(ts);
    }
    std::string bl = str(env, btnLabel), bu = str(env, btnUrl);
    if (!bl.empty() && !bu.empty()) { discordpp::ActivityButton b; b.SetLabel(bl); b.SetUrl(bu); a.AddButton(b); }
    g_client->UpdateRichPresence(a, [](discordpp::ClientResult r) {
        if (r.Successful()) push("presence:ok"); else push("error:presence:" + r.Error());
    });
}

JNIEXPORT void JNICALL Java_com_slayer_abyss_DiscordNative_clear(JNIEnv*, jclass) { if (g_client) g_client->ClearRichPresence(); }

JNIEXPORT void JNICALL Java_com_slayer_abyss_DiscordNative_authorize(JNIEnv*, jclass) {
    if (!g_client) return;
    auto v = g_client->CreateAuthorizationCodeVerifier();
    g_verifier = v.Verifier();
    discordpp::AuthorizationArgs args;
    args.SetClientId(g_appId);
    args.SetScopes(discordpp::Client::GetDefaultPresenceScopes());
    args.SetCodeChallenge(v.Challenge());
    g_client->Authorize(args, [](discordpp::ClientResult r, std::string code, std::string redirectUri) {
        if (!r.Successful()) { push("error:auth:" + r.Error()); return; }
        g_client->GetToken(g_appId, code, g_verifier, redirectUri,
            [](discordpp::ClientResult r2, std::string access, std::string refresh, discordpp::AuthorizationTokenType, int32_t expires, std::string) {
                onTokens(r2, access, refresh, expires);
            });
    });
}

JNIEXPORT void JNICALL Java_com_slayer_abyss_DiscordNative_refresh(JNIEnv* env, jclass, jstring refreshToken) {
    if (!g_client) return;
    g_client->RefreshToken(g_appId, str(env, refreshToken),
        [](discordpp::ClientResult r, std::string access, std::string refresh, discordpp::AuthorizationTokenType, int32_t expires, std::string) {
            onTokens(r, access, refresh, expires);
        });
}

JNIEXPORT void JNICALL Java_com_slayer_abyss_DiscordNative_disconnect(JNIEnv*, jclass) { if (g_client) { g_client->ClearRichPresence(); g_client->Disconnect(); } }

}
