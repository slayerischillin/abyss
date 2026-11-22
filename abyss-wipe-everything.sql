-- Abyss: WIPE EVERYTHING. Every account and email, usernames, profile pictures,
-- uploaded covers and songs, synced libraries, devices. This cannot be undone.
do $$
declare t text;
begin
  -- usernames, synced libraries, devices, shared covers, sign-in handoffs
  foreach t in array array['user_data','profiles','connect_devices','cover_picks','auth_handoff'] loop
    if to_regclass('public.'||t) is not null then execute format('truncate table public.%I', t); end if;
  end loop;
  -- profile pictures, uploaded covers and songs
  begin
    delete from storage.objects where bucket_id in ('avatars','covers','songs');
  exception when others then
    raise notice 'Supabase blocked deleting files here. Empty the avatars, covers and songs buckets in Storage instead.';
  end;
end $$;
-- every account and email
delete from auth.users;
