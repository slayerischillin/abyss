-- Abyss: private Lil Uzi Vert library. Run once in Supabase > SQL Editor.
-- Holds the Google Drive connection and the song list. Nobody can read it from the app: only the uzi function can.
create table if not exists public.uzi_cfg (k text primary key, v jsonb);
alter table public.uzi_cfg enable row level security;
revoke all on public.uzi_cfg from anon, authenticated;
