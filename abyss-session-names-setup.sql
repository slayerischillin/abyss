-- Abyss: names for session edits that everyone sees. Only @slayer can change them.
create table if not exists public.abyss_admins (user_id uuid primary key references auth.users(id) on delete cascade);
alter table public.abyss_admins enable row level security;
revoke all on public.abyss_admins from anon, authenticated;
insert into public.abyss_admins (user_id) select id from public.profiles where lower(username) = 'slayer' on conflict do nothing;

create or replace function public.is_abyss_admin() returns boolean
language sql security definer set search_path = public stable as $$
  select exists (select 1 from public.abyss_admins where user_id = auth.uid())
$$;
grant execute on function public.is_abyss_admin() to authenticated;

create table if not exists public.session_names (
  path text primary key check (length(path) < 600),
  name text not null check (length(name) between 1 and 200),
  updated_at timestamptz not null default now()
);
alter table public.session_names enable row level security;
drop policy if exists "names are public" on public.session_names;
drop policy if exists "admin writes names" on public.session_names;
create policy "names are public" on public.session_names for select using (true);
create policy "admin writes names" on public.session_names for all to authenticated using (public.is_abyss_admin()) with check (public.is_abyss_admin());
grant select on public.session_names to anon, authenticated;
grant insert, update, delete on public.session_names to authenticated;

select count(*) as admins from public.abyss_admins;
