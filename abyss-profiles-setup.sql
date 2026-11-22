-- Abyss: fix syncing + usernames and profile pictures (animated GIFs allowed)
-- Paste all of this into Supabase > SQL Editor > New query, then press Run.

-- 1) Fix "Sync failed: HTTP 403 ... GRANT SELECT": let signed-in users use their own sync row
--    (the row-level rules already stop anyone from seeing anyone else's).
grant select, insert, update, delete on public.user_data to authenticated;


-- 2) Profiles: a username and a picture for each account
create table if not exists public.profiles (
  id         uuid primary key references auth.users(id) on delete cascade,
  username   text not null check (username ~ '^[A-Za-z0-9._]{3,20}$'),
  avatar_url text check (avatar_url is null or length(avatar_url) < 600),
  updated_at timestamptz not null default now()
);
-- usernames are unique, ignoring capital letters
create unique index if not exists profiles_username_lower on public.profiles (lower(username));

alter table public.profiles enable row level security;
drop policy if exists "profiles are public" on public.profiles;
drop policy if exists "own profile insert" on public.profiles;
drop policy if exists "own profile update" on public.profiles;
create policy "profiles are public" on public.profiles for select using (true);
create policy "own profile insert" on public.profiles for insert with check (auth.uid() = id);
create policy "own profile update" on public.profiles for update using (auth.uid() = id) with check (auth.uid() = id);
grant select on public.profiles to anon, authenticated;
grant insert, update on public.profiles to authenticated;

-- 3) Picture storage: a public "avatars" folder; each user can only change files in their own sub-folder
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('avatars', 'avatars', true, 8388608, array['image/png','image/jpeg','image/gif','image/webp'])
on conflict (id) do update set public = true, file_size_limit = 8388608,
  allowed_mime_types = array['image/png','image/jpeg','image/gif','image/webp'];

drop policy if exists "avatar upload own folder" on storage.objects;
drop policy if exists "avatar update own folder" on storage.objects;
drop policy if exists "avatar delete own folder" on storage.objects;
create policy "avatar upload own folder" on storage.objects for insert to authenticated
  with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "avatar update own folder" on storage.objects for update to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "avatar delete own folder" on storage.objects for delete to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists "avatar read own folder" on storage.objects;
create policy "avatar read own folder" on storage.objects for select to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
