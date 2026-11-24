// Abyss: private Lil Uzi Vert library.
// Streams songs straight from the owner's Google Drive. Only accounts in public.unleaked_owner can list or play them.
// Secrets to set in Supabase (Edge Functions > Secrets): GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET
// Deploy with "Verify JWT" turned OFF (this function checks the user itself, so audio players can use plain links).

const ROOTS = [
  "1XkGfhVRG3XfMjWwezwyRuUYfMuHAkUdX", // Lil Uzi Vert Discrogaphy
  "1I2_DeXGChfg8pisliiPVhPeXtOA8oLtZ", // Lil Uzi Vert - W.H.2.U
];
const SB = Deno.env.get("SUPABASE_URL")!;
const SRK = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const GID = Deno.env.get("GOOGLE_CLIENT_ID") || "";
const GSEC = Deno.env.get("GOOGLE_CLIENT_SECRET") || "";
const SELF = SB.replace(/\/$/, "") + "/functions/v1/uzi";
const AUDIO = /\.(mp3|m4a|wav|flac|ogg|aac|opus|aiff?)$/i;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, range",
  "Access-Control-Expose-Headers": "content-length, content-range, accept-ranges, content-type",
};
const json = (b: unknown, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } });
const page = (msg: string) => new Response(`<!doctype html><meta name=viewport content="width=device-width"><body style="background:#0b0910;color:#fff;font:16px system-ui;display:grid;place-items:center;height:90vh;text-align:center"><div><h2>Abyss</h2><p>${msg}</p></div>`, { headers: { "Content-Type": "text/html; charset=utf-8" } });

// ---- private key/value table (service role only) ----
async function kvGet(k: string) {
  const r = await fetch(`${SB}/rest/v1/uzi_cfg?k=eq.${encodeURIComponent(k)}&select=v`, { headers: { apikey: SRK, Authorization: `Bearer ${SRK}` } });
  if (!r.ok) throw new Error("Run abyss-uzi-setup.sql first");
  const a = await r.json(); return a[0]?.v ?? null;
}
async function kvSet(k: string, v: unknown) {
  await fetch(`${SB}/rest/v1/uzi_cfg?on_conflict=k`, { method: "POST", headers: { apikey: SRK, Authorization: `Bearer ${SRK}`, "Content-Type": "application/json", Prefer: "resolution=merge-duplicates,return=minimal" }, body: JSON.stringify({ k, v }) });
}

// ---- who is asking ----
async function ownerFromJwt(jwt: string): Promise<string | null> {
  const u = await fetch(`${SB}/auth/v1/user`, { headers: { apikey: ANON, Authorization: `Bearer ${jwt}` } });
  if (!u.ok) return null;
  const id = (await u.json()).id as string;
  return (await isOwner(id)) ? id : null;
}
const ownerCache = new Map<string, number>();
async function isOwner(uid: string) {
  const c = ownerCache.get(uid); if (c && c > Date.now()) return true;
  const r = await fetch(`${SB}/rest/v1/unleaked_owner?user_id=eq.${uid}&select=user_id`, { headers: { apikey: SRK, Authorization: `Bearer ${SRK}` } });
  const ok = r.ok && (await r.json()).length > 0;
  if (ok) ownerCache.set(uid, Date.now() + 10 * 60 * 1000); else ownerCache.delete(uid);
  return ok;
}
// play tickets: signed, tied to one account, last 12 hours (so long pauses don't break playback)
const enc = new TextEncoder();
let hkey: CryptoKey | null = null;
async function hmac(s: string) {
  hkey ??= await crypto.subtle.importKey("raw", enc.encode("uzi-ticket:" + SRK), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const b = new Uint8Array(await crypto.subtle.sign("HMAC", hkey, enc.encode(s)));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
async function ticket(uid: string) { const body = uid + "." + (Date.now() + 12 * 3600 * 1000); return body + "." + await hmac(body); }
async function fromTicket(t: string): Promise<string | null> {
  const p = t.split("."); if (p.length !== 3) return null;
  if (await hmac(p[0] + "." + p[1]) !== p[2] || +p[1] < Date.now()) return null;
  return (await isOwner(p[0])) ? p[0] : null;
}

// ---- Google ----
let gTok = { v: "", exp: 0 };
async function google() {
  if (gTok.exp > Date.now() + 60000) return gTok.v;
  const rt = await kvGet("refresh"); if (!rt) throw new Error("setup");
  const r = await fetch("https://oauth2.googleapis.com/token", { method: "POST", body: new URLSearchParams({ client_id: GID, client_secret: GSEC, refresh_token: rt, grant_type: "refresh_token" }) });
  const d = await r.json(); if (!d.access_token) throw new Error("setup");
  gTok = { v: d.access_token, exp: Date.now() + (d.expires_in || 3600) * 1000 }; return gTok.v;
}
async function children(id: string, tok: string) {
  const out: any[] = []; let pt = "";
  do {
    const u = new URL("https://www.googleapis.com/drive/v3/files");
    u.searchParams.set("q", `'${id}' in parents and trashed=false`);
    u.searchParams.set("fields", "nextPageToken,files(id,name,mimeType,size)");
    u.searchParams.set("pageSize", "1000"); u.searchParams.set("supportsAllDrives", "true"); u.searchParams.set("includeItemsFromAllDrives", "true");
    if (pt) u.searchParams.set("pageToken", pt);
    const r = await fetch(u, { headers: { Authorization: `Bearer ${tok}` } }); if (!r.ok) throw new Error("Drive " + r.status);
    const d = await r.json(); out.push(...d.files); pt = d.nextPageToken || "";
  } while (pt);
  return out;
}
let memo: { idx: any; exp: number } = { idx: null, exp: 0 };
async function cachedIndex() { if (memo.idx && memo.exp > Date.now()) return memo.idx; const idx = await kvGet("index"); memo = { idx, exp: Date.now() + 5 * 60 * 1000 }; return idx; }
const eraName = (s: string) => s.replace(/^lil uzi vert\s*-\s*/i, "").replace(/\s*part\s*\d+\s*$/i, "").replace(/\s*\(\d+\)\s*$/, "").trim();
async function buildIndex() {
  const tok = await google(), items: any[] = [];
  const walk = async (id: string, era: string, path: string[], depth: number) => {
    const kids = await children(id, tok);
    for (const f of kids) {
      if (f.mimeType === "application/vnd.google-apps.folder") { if (depth < 6) await walk(f.id, era, [...path, f.name], depth + 1); }
      else if (AUDIO.test(f.name) || /^audio\//.test(f.mimeType)) items.push({ f: f.id, n: f.name, e: era, p: path.join(" / "), s: +f.size || 0 });
    }
  };
  for (const r of ROOTS) {
    const meta = await (await fetch(`https://www.googleapis.com/drive/v3/files/${r}?fields=name&supportsAllDrives=true`, { headers: { Authorization: `Bearer ${tok}` } })).json();
    const top = await children(r, tok);
    const loose = top.filter((f) => f.mimeType !== "application/vnd.google-apps.folder");
    for (const f of loose) if (AUDIO.test(f.name)) items.push({ f: f.id, n: f.name, e: eraName(meta.name || "Lil Uzi Vert"), p: "", s: +f.size || 0 });
    for (const d of top.filter((f) => f.mimeType === "application/vnd.google-apps.folder")) await walk(d.id, eraName(d.name), [d.name], 1);
  }
  const idx = { at: Date.now(), items };
  await kvSet("index", idx); memo = { idx, exp: Date.now() + 5 * 60 * 1000 }; return idx;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  const url = new URL(req.url), q = url.searchParams;
  try {
    // Google sends the owner back here after they allow access
    if (q.get("code") && q.get("state")) {
      const want = await kvGet("state");
      if (!want || want.s !== q.get("state") || want.exp < Date.now()) return page("That link expired. Start again from Abyss.");
      const r = await fetch("https://oauth2.googleapis.com/token", { method: "POST", body: new URLSearchParams({ code: q.get("code")!, client_id: GID, client_secret: GSEC, redirect_uri: SELF, grant_type: "authorization_code" }) });
      const d = await r.json();
      if (!d.refresh_token) return page("Google did not give Abyss offline access. Remove Abyss from your Google account's third-party access and try again.");
      await kvSet("refresh", d.refresh_token); await kvSet("state", null); gTok = { v: "", exp: 0 };
      try { await buildIndex(); } catch (_) { /* the app retries */ }
      return page("Google Drive is connected. Go back to Abyss.");
    }
    // the owner starts the Google connection from Abyss
    if (q.get("setup")) {
      const uid = await fromTicket(q.get("k") || ""); if (!uid) return page("Only the owner can do this.");
      if (!GID || !GSEC) return page("Add GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET to the function's secrets first.");
      const s = crypto.randomUUID(); await kvSet("state", { s, exp: Date.now() + 15 * 60 * 1000 });
      const g = new URL("https://accounts.google.com/o/oauth2/v2/auth");
      g.search = new URLSearchParams({ client_id: GID, redirect_uri: SELF, response_type: "code", scope: "https://www.googleapis.com/auth/drive.readonly", access_type: "offline", prompt: "consent", state: s }).toString();
      return Response.redirect(g.toString(), 302);
    }
    // song list (needs a signed-in owner)
    if (q.get("list")) {
      const jwt = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
      const uid = await ownerFromJwt(jwt); if (!uid) return json({ error: "not allowed" }, 403);
      const k = await ticket(uid);
      if (!(await kvGet("refresh"))) return json({ setup: true, ticket: k });
      let idx = await kvGet("index");
      if (!idx || q.get("fresh") || Date.now() - idx.at > 6 * 3600 * 1000) { try { idx = await buildIndex(); } catch (e) { if (!idx) throw e; } }
      return json({ ticket: k, at: idx.at, items: idx.items });
    }
    // play one song (or read its first bytes for the cover)
    const f = q.get("f");
    if (f) {
      const uid = await fromTicket(q.get("k") || ""); if (!uid) return new Response("not allowed", { status: 403, headers: cors });
      const idx = await cachedIndex();
      if (!idx || !idx.items.some((x: any) => x.f === f)) return new Response("not found", { status: 404, headers: cors });
      const h: Record<string, string> = { Authorization: `Bearer ${await google()}` };
      const range = req.headers.get("range"); if (range) h.Range = range;
      const g = await fetch(`https://www.googleapis.com/drive/v3/files/${f}?alt=media&supportsAllDrives=true`, { headers: h });
      if (!g.ok && g.status !== 206) return new Response("drive " + g.status, { status: 502, headers: cors });
      const out = new Headers(cors);
      for (const k of ["content-type", "content-length", "content-range", "accept-ranges", "etag", "last-modified"]) { const v = g.headers.get(k); if (v) out.set(k, v); }
      if (!out.get("accept-ranges")) out.set("accept-ranges", "bytes");
      const it = idx.items.find((x: any) => x.f === f);
      if (/octet-stream/.test(out.get("content-type") || "") && it) out.set("content-type", /\.m4a$/i.test(it.n) ? "audio/mp4" : /\.wav$/i.test(it.n) ? "audio/wav" : /\.flac$/i.test(it.n) ? "audio/flac" : "audio/mpeg");
      out.set("Cache-Control", "private, max-age=3600");
      return new Response(g.body, { status: g.status, headers: out });
    }
    return json({ ok: true });
  } catch (e) {
    const m = String((e as Error).message || e);
    if (m === "setup") return json({ setup: true }, 200);
    return json({ error: m }, 500);
  }
});
