-- Abyss: shared fan-made covers. Paste into Supabase > SQL Editor > New query, press Run.

-- Every cover someone picks or uploads, with how many people picked it
create table if not exists public.cover_picks (
  song_id text not null,
  url     text not null check (url ~ '^https://' and length(url) < 1000),
  picks   int  not null default 1,
  updated_at timestamptz not null default now(),
  primary key (song_id, url)
);
create index if not exists cover_picks_song on public.cover_picks (song_id, picks desc);
alter table public.cover_picks enable row level security;
drop policy if exists "covers are public" on public.cover_picks;
create policy "covers are public" on public.cover_picks for select using (true);
grant select on public.cover_picks to anon, authenticated;

-- The app adds picks only through this function (no direct writes)
create or replace function public.pick_cover(p_song text, p_url text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if p_url !~ '^https://' or length(p_url) >= 1000 or length(p_song) > 100 then return; end if;
  insert into cover_picks (song_id, url) values (p_song, p_url)
  on conflict (song_id, url) do update set picks = cover_picks.picks + 1, updated_at = now();
end $$;
revoke all on function public.pick_cover(text, text) from public;
grant execute on function public.pick_cover(text, text) to anon, authenticated;

-- Storage for covers people upload from their device (signed-in users, their own folder)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('covers', 'covers', true, 4194304, array['image/jpeg','image/png','image/webp'])
on conflict (id) do update set public = true, file_size_limit = 4194304,
  allowed_mime_types = array['image/jpeg','image/png','image/webp'];
drop policy if exists "cover read own folder" on storage.objects;
drop policy if exists "cover upload own folder" on storage.objects;
create policy "cover read own folder" on storage.objects for select to authenticated
  using (bucket_id = 'covers' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "cover upload own folder" on storage.objects for insert to authenticated
  with check (bucket_id = 'covers' and (storage.foldername(name))[1] = auth.uid()::text);
