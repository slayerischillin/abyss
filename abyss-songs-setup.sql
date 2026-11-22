-- Abyss: sync your own songs (local files) between your devices.
-- Paste into Supabase > SQL Editor > New query, press Run.
-- Songs are private: only you can see, add or delete your own.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('songs', 'songs', false, 52428800, null)
on conflict (id) do update set public = false, file_size_limit = 52428800, allowed_mime_types = null;

drop policy if exists "songs read own" on storage.objects;
drop policy if exists "songs add own" on storage.objects;
drop policy if exists "songs update own" on storage.objects;
drop policy if exists "songs delete own" on storage.objects;
create policy "songs read own" on storage.objects for select to authenticated
  using (bucket_id = 'songs' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "songs add own" on storage.objects for insert to authenticated
  with check (bucket_id = 'songs' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "songs update own" on storage.objects for update to authenticated
  using (bucket_id = 'songs' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "songs delete own" on storage.objects for delete to authenticated
  using (bucket_id = 'songs' and (storage.foldername(name))[1] = auth.uid()::text);
