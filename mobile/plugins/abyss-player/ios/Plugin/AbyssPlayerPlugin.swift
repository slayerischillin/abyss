import Foundation
import Capacitor
import AVFoundation
import MediaPlayer
import UIKit

/// Plays Abyss through the iPhone's own audio player, so music keeps going on the home screen
/// and lock screen, with lock screen / Control Center controls. Mirrors the Android plugin.
@objc(AbyssPlayerPlugin)
public class AbyssPlayerPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "AbyssPlayerPlugin"
    public let jsName = "AbyssPlayer"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "load", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setNext", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "play", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pause", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "seek", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setVolume", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setRate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connect", returnType: CAPPluginReturnPromise)
    ]

    private let player = AVQueuePlayer()
    private var wants = false
    private var rate: Float = 1
    private var info: [String: String] = [:]
    private var nextInfo: [String: String] = [:]
    private var timer: Timer?
    private var art: MPMediaItemArtwork?
    private var artURL = ""
    private var obs: [NSKeyValueObservation] = []

    override public func load() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default, options: [])
        try? s.setActive(true)
        player.automaticallyWaitsToMinimizeStalling = true
        player.actionAtItemEnd = .advance
        NotificationCenter.default.addObserver(self, selector: #selector(itemEnded(_:)), name: .AVPlayerItemDidPlayToEndTime, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(interrupted(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        obs.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in DispatchQueue.main.async { self?.emitState(); self?.nowPlaying() } })
        remote()
        DispatchQueue.main.async { UIApplication.shared.beginReceivingRemoteControlEvents() }
    }

    // MARK: JS methods
    @objc func load(_ call: CAPPluginCall) {
        guard let s = call.getString("url"), let u = URL(string: s) else { call.reject("bad url"); return }
        let meta = pick(call); let at = call.getDouble("position") ?? 0; let auto = call.getBool("autoplay") ?? false
        DispatchQueue.main.async {
            self.player.removeAllItems()
            let it = AVPlayerItem(url: u)
            self.player.insert(it, after: nil)
            self.info = meta; self.info["url"] = s; self.nextInfo = [:]
            if at > 0 { self.player.seek(to: CMTime(seconds: at, preferredTimescale: 600)) }
            try? AVAudioSession.sharedInstance().setActive(true)
            self.wants = auto
            if auto { self.player.playImmediately(atRate: self.rate) } else { self.player.pause() }
            self.loadArt(meta["art"] ?? ""); self.nowPlaying(); self.emitState(); self.tick()
            call.resolve()
        }
    }

    @objc func setNext(_ call: CAPPluginCall) {
        let s = call.getString("url") ?? ""; let meta = pick(call)
        DispatchQueue.main.async {
            let items = self.player.items()
            for it in items.dropFirst() { self.player.remove(it) }
            if !s.isEmpty, let u = URL(string: s), !items.isEmpty {
                self.player.insert(AVPlayerItem(url: u), after: self.player.items().last)
                self.nextInfo = meta; self.nextInfo["url"] = s
            } else { self.nextInfo = [:] }
            call.resolve()
        }
    }

    @objc func updateInfo(_ call: CAPPluginCall) {
        let meta = pick(call)
        DispatchQueue.main.async { let url = self.info["url"]; self.info = meta; self.info["url"] = url; self.loadArt(meta["art"] ?? ""); self.nowPlaying(); call.resolve() }
    }

    @objc func play(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            try? AVAudioSession.sharedInstance().setActive(true)
            if let it = self.player.currentItem, it.duration.isValid, CMTimeCompare(it.currentTime(), it.duration) >= 0 { self.player.seek(to: .zero) }
            self.wants = true; self.player.playImmediately(atRate: self.rate); self.emitState(); self.nowPlaying(); self.tick(); call.resolve()
        }
    }
    @objc func pause(_ call: CAPPluginCall) { DispatchQueue.main.async { self.wants = false; self.player.pause(); self.emitState(); self.nowPlaying(); call.resolve() } }
    @objc func seek(_ call: CAPPluginCall) {
        let p = call.getDouble("position") ?? 0
        DispatchQueue.main.async {
            self.player.seek(to: CMTime(seconds: p, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { _ in
                DispatchQueue.main.async { self.emit(["type": "seeked"]); self.emitState(); self.nowPlaying() } }
            call.resolve()
        }
    }
    @objc func setVolume(_ call: CAPPluginCall) { let v = Float(call.getDouble("volume") ?? 1); DispatchQueue.main.async { self.player.volume = max(0, min(1, v)); call.resolve() } }
    @objc func setRate(_ call: CAPPluginCall) {
        let r = Float(call.getDouble("rate") ?? 1)
        DispatchQueue.main.async { self.rate = r; if self.wants { self.player.rate = r }; self.nowPlaying(); call.resolve() }
    }
    @objc func stop(_ call: CAPPluginCall) { DispatchQueue.main.async { self.wants = false; self.player.pause(); self.player.removeAllItems(); self.emitState(); call.resolve() } }

    // MARK: events
    @objc private func itemEnded(_ n: Notification) {
        guard let it = n.object as? AVPlayerItem else { return }
        DispatchQueue.main.async {
            let hasNext = !self.nextInfo.isEmpty && self.player.items().contains(where: { $0 !== it })
            if hasNext {
                // the queued song started by itself (works even while the page is asleep)
                self.info = self.nextInfo; self.nextInfo = [:]
                self.loadArt(self.info["art"] ?? ""); self.nowPlaying()
                self.emit(["type": "advanced", "url": self.info["url"] ?? ""])
            } else {
                self.wants = false
                self.emit(["type": "ended"])
            }
            self.emitState()
        }
    }

    @objc private func interrupted(_ n: Notification) {
        guard let t = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, let type = AVAudioSession.InterruptionType(rawValue: t) else { return }
        DispatchQueue.main.async {
            if type == .ended {
                let o = (n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map { AVAudioSession.InterruptionOptions(rawValue: $0) } ?? []
                if self.wants && o.contains(.shouldResume) { try? AVAudioSession.sharedInstance().setActive(true); self.player.playImmediately(atRate: self.rate) }
            }
            self.emitState()
        }
    }

    private func tick() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.emitState()
            if !self.wants && self.player.timeControlStatus == .paused { self.timer?.invalidate(); self.timer = nil }
        }
    }

    private func emitState() {
        let pos = player.currentTime().seconds
        let d = player.currentItem?.duration.seconds ?? 0
        emit(["type": "state", "playing": player.timeControlStatus == .playing, "wants": wants,
              "position": pos.isFinite ? pos : 0, "duration": (d.isFinite && d > 0) ? d : 0,
              "buffering": player.timeControlStatus == .waitingToPlayAtSpecifiedRate])
    }
    private func emit(_ o: [String: Any]) { notifyListeners("player", data: o, retainUntilConsumed: (o["type"] as? String) != "state") }

    // MARK: lock screen
    private func pick(_ c: CAPPluginCall) -> [String: String] {
        return ["title": c.getString("title") ?? "Abyss", "artist": c.getString("artist") ?? "", "album": c.getString("album") ?? "", "art": c.getString("art") ?? ""]
    }
    private func nowPlaying() {
        var i: [String: Any] = [MPMediaItemPropertyTitle: info["title"] ?? "Abyss", MPMediaItemPropertyArtist: info["artist"] ?? "", MPMediaItemPropertyAlbumTitle: info["album"] ?? ""]
        let d = player.currentItem?.duration.seconds ?? 0
        if d.isFinite && d > 0 { i[MPMediaItemPropertyPlaybackDuration] = d }
        let p = player.currentTime().seconds
        i[MPNowPlayingInfoPropertyElapsedPlaybackTime] = p.isFinite ? p : 0
        i[MPNowPlayingInfoPropertyPlaybackRate] = player.timeControlStatus == .playing ? Double(rate) : 0
        if let a = art { i[MPMediaItemPropertyArtwork] = a }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = i
    }
    private func loadArt(_ s: String) {
        guard s != artURL else { return }
        artURL = s; art = nil
        guard let u = URL(string: s), s.hasPrefix("https") else { return }
        URLSession.shared.dataTask(with: u) { [weak self] data, _, _ in
            guard let self = self, let data = data, let img = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard self.artURL == s else { return }
                self.art = MPMediaItemArtwork(boundsSize: img.size) { _ in img }; self.nowPlaying()
            }
        }.resume()
    }
    private func remote() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in guard let s = self else { return .commandFailed }; s.wants = true; s.player.playImmediately(atRate: s.rate); s.emitState(); s.nowPlaying(); s.tick(); return .success }
        c.pauseCommand.addTarget { [weak self] _ in guard let s = self else { return .commandFailed }; s.wants = false; s.player.pause(); s.emitState(); s.nowPlaying(); return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in guard let s = self else { return .commandFailed }
            if s.wants { s.wants = false; s.player.pause() } else { s.wants = true; s.player.playImmediately(atRate: s.rate); s.tick() }; s.emitState(); s.nowPlaying(); return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in
            guard let s = self else { return .commandFailed }
            if s.player.items().count > 1, !s.nextInfo.isEmpty {   // play the queued song right away, even if the page is asleep
                s.player.advanceToNextItem(); s.info = s.nextInfo; s.nextInfo = [:]; s.wants = true; s.player.playImmediately(atRate: s.rate)
                s.loadArt(s.info["art"] ?? ""); s.nowPlaying(); s.emit(["type": "advanced", "url": s.info["url"] ?? ""]); s.emitState(); s.tick()
            } else { s.emit(["type": "action", "action": "next"]) }
            return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in self?.emit(["type": "action", "action": "prev"]); return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            guard let s = self, let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            s.player.seek(to: CMTime(seconds: e.positionTime, preferredTimescale: 600)) { _ in DispatchQueue.main.async { s.emitState(); s.nowPlaying(); s.emit(["type": "seeked"]) } }
            return .success }
    }
    // MARK: Abyss Connect while the app is asleep
    // When the phone is locked the page can't run, so the player itself checks in with your account,
    // follows play / pause / skip / seek from your other devices, and reports what it's playing.
    private var cn: [String: Any] = [:]
    private var cnTimer: Timer?
    private var lastPlay: Double = 0
    @objc func connect(_ call: CAPPluginCall) {
        var o: [String: Any] = [:]
        for k in ["url", "key", "token", "dev", "user", "name", "platform", "rawUrl"] { if let v = call.getString(k) { o[k] = v } }
        if let r = call.getObject("raw") { o["raw"] = r }
        for k in ["title", "artist", "sub", "cover"] { o[k] = call.getString(k) ?? "" }
        DispatchQueue.main.async {
            self.cn = o
            if self.cnTimer == nil {
                self.cnTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.cnBeat() }
            }
            call.resolve()
        }
    }
    private func cnBeat() {
        guard UIApplication.shared.applicationState != .active,
              let base = cn["url"] as? String, let key = cn["key"] as? String, let tok = cn["token"] as? String, let dev = cn["dev"] as? String,
              let u = URL(string: base + "/rest/v1/connect_devices?select=cmd&id=eq." + (dev.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? dev)) else { return }
        var r = URLRequest(url: u); r.setValue(key, forHTTPHeaderField: "apikey"); r.setValue("Bearer " + tok, forHTTPHeaderField: "Authorization")
        URLSession.shared.dataTask(with: r) { [weak self] data, _, _ in
            guard let self = self else { return }
            var cmd: [String: Any]? = nil
            if let d = data, let a = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]], let f = a.first { cmd = f["cmd"] as? [String: Any] }
            DispatchQueue.main.async {
                var handled = false
                if let c = cmd, let id = c["id"] as? String {
                    let age = Date().timeIntervalSince1970 * 1000 - (Double(id.prefix(13)) ?? 0)
                    if age < 60000 { handled = self.runCmd(c) }
                    self.emit(["type": "cmdDone", "id": id])
                }
                self.cnReport(clearCmd: cmd != nil, handled: handled)
            }
        }.resume()
    }
    private func runCmd(_ c: [String: Any]) -> Bool {
        let t = c["type"] as? String ?? ""
        switch t {
        case "pause": wants = false; player.pause()
        case "play": wants = true; try? AVAudioSession.sharedInstance().setActive(true); player.playImmediately(atRate: rate); tick()
        case "toggle": if wants { wants = false; player.pause() } else { wants = true; player.playImmediately(atRate: rate); tick() }
        case "seek": if let p = c["pos"] as? Double { player.seek(to: CMTime(seconds: p, preferredTimescale: 600)) }
        case "prev": player.seek(to: .zero)
        case "next":
            if player.items().count > 1, !nextInfo.isEmpty {
                player.advanceToNextItem(); info = nextInfo; nextInfo = [:]; wants = true; player.playImmediately(atRate: rate)
                loadArt(info["art"] ?? ""); emit(["type": "advanced", "url": info["url"] ?? ""]); tick()
            } else { emit(["type": "action", "action": "next"]) }
        default: return false
        }
        emitState(); nowPlaying(); return true
    }
    private func cnReport(clearCmd: Bool, handled: Bool) {
        guard let base = cn["url"] as? String, let key = cn["key"] as? String, let tok = cn["token"] as? String, let dev = cn["dev"] as? String,
              let u = URL(string: base + "/rest/v1/connect_devices?id=eq." + (dev.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? dev)) else { return }
        let now = Date().timeIntervalSince1970 * 1000
        let paused = !(wants && player.timeControlStatus != .paused)
        if !paused { lastPlay = now }
        let pos = player.currentTime().seconds, d = player.currentItem?.duration.seconds ?? 0
        var st: [String: Any] = ["title": info["title"] ?? (cn["title"] as? String ?? ""), "artist": info["artist"] ?? (cn["artist"] as? String ?? ""),
                                 "sub": cn["sub"] as? String ?? "", "cover": info["art"] ?? (cn["cover"] as? String ?? ""),
                                 "pos": pos.isFinite ? pos : 0, "dur": (d.isFinite && d > 0) ? d : 0, "paused": paused, "at": now, "lastPlay": lastPlay]
        if let raw = cn["raw"], let ru = cn["rawUrl"] as? String, ru == (info["url"] ?? "") { st["raw"] = raw }
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var body: [String: Any] = ["seen": f.string(from: Date()), "state": st]
        if clearCmd { body["cmd"] = NSNull() }
        if handled && !paused { body["active_at"] = f.string(from: Date()) }
        var r = URLRequest(url: u); r.httpMethod = "PATCH"
        r.setValue(key, forHTTPHeaderField: "apikey"); r.setValue("Bearer " + tok, forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type"); r.setValue("return=minimal", forHTTPHeaderField: "Prefer")
        r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: r).resume()
    }

}
