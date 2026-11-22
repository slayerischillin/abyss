-- Abyss: automatic "Continue with Google" (no copy-pasting codes)
-- Paste all of this into Supabase > SQL Editor > New query, then press Run.
--
-- How it works: when you sign in with Google, the sign-in page drops the one-time code here
-- under a random key that only your copy of Abyss knows. Abyss picks it up, deletes it, and
-- finishes signing in. The code is useless on its own (it needs a secret that never leaves
-- your device), and leftovers are deleted after 10 minutes.

create table if not exists public.auth_handoff (
  h          text primary key,
  code       text not null,
  created_at timestamptz not null default now()
);

-- Nobody can read or write the table directly; only the two functions below.
alter table public.auth_handoff enable row level security;

create or replace function public.put_handoff(p_h text, p_code text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if p_h is null or length(p_h) < 32 or length(p_h) > 128 then raise exception 'bad key'; end if;
  if p_code is null or length(p_code) = 0 or length(p_code) > 512 then raise exception 'bad code'; end if;
  delete from public.auth_handoff where created_at < now() - interval '10 minutes';
  insert into public.auth_handoff (h, code) values (p_h, p_code)
  on conflict (h) do update set code = excluded.code, created_at = now();
end;
$$;

create or replace function public.take_handoff(p_h text)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare c text;
begin
  if p_h is null or length(p_h) < 32 then return null; end if;
  delete from public.auth_handoff
   where h = p_h and created_at > now() - interval '10 minutes'
  returning code into c;
  return c;
end;
$$;

revoke all on function public.put_handoff(text, text) from public;
revoke all on function public.take_handoff(text) from public;
grant execute on function public.put_handoff(text, text) to anon, authenticated;
grant execute on function public.take_handoff(text) to anon, authenticated;
