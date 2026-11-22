-- Abyss Connect: see your devices and move playback between them.
-- Paste into Supabase > SQL Editor > New query, press Run.
create table if not exists public.connect_devices (
  id         text primary key check (length(id) < 40),
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name       text check (length(name) <= 40),
  platform   text check (length(platform) <= 10),
  seen       timestamptz,
  active_at  timestamptz,
  state      jsonb,
  cmd        jsonb
);
create index if not exists connect_devices_user on public.connect_devices (user_id);
alter table public.connect_devices enable row level security;
drop policy if exists "own devices" on public.connect_devices;
create policy "own devices" on public.connect_devices for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
grant select, insert, update, delete on public.connect_devices to authenticated;
