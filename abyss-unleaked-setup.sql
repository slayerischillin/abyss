-- Abyss: "Unleaked", a private playlist only @slayer can see or play.
-- Run once in Supabase > SQL Editor > New query. Your account must already have the username "slayer".
create table if not exists public.unleaked_owner (user_id uuid primary key references auth.users(id) on delete cascade);
alter table public.unleaked_owner enable row level security;
revoke all on public.unleaked_owner from anon, authenticated;
-- locks access to the account that has the username "slayer" right now (changing usernames later won't move it)
insert into public.unleaked_owner (user_id)
  select id from public.profiles where lower(username) = 'slayer'
  on conflict do nothing;

create or replace function public.is_unleaked_owner() returns boolean
language sql security definer set search_path = public stable as $$
  select exists (select 1 from public.unleaked_owner where user_id = auth.uid())
$$;
grant execute on function public.is_unleaked_owner() to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('unleaked', 'unleaked', false, 52428800, null)
on conflict (id) do update set public = false, file_size_limit = 52428800;

drop policy if exists "unleaked read" on storage.objects;
drop policy if exists "unleaked add" on storage.objects;
drop policy if exists "unleaked update" on storage.objects;
drop policy if exists "unleaked delete" on storage.objects;
create policy "unleaked read" on storage.objects for select to authenticated using (bucket_id = 'unleaked' and public.is_unleaked_owner());
create policy "unleaked add" on storage.objects for insert to authenticated with check (bucket_id = 'unleaked' and public.is_unleaked_owner());
create policy "unleaked update" on storage.objects for update to authenticated using (bucket_id = 'unleaked' and public.is_unleaked_owner());
create policy "unleaked delete" on storage.objects for delete to authenticated using (bucket_id = 'unleaked' and public.is_unleaked_owner());

-- check: this should show 1 row
select count(*) as owners from public.unleaked_owner;
