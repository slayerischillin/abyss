-- Abyss: WIPE EVERYTHING. Deletes every account (emails included) and all saved data.
-- This cannot be undone.
-- Before running: Storage > each bucket (avatars, covers, songs) > "..." > Empty bucket.
do $$
declare t text;
begin
  foreach t in array array['user_data','profiles','connect_devices','cover_picks','auth_handoff'] loop
    if to_regclass('public.'||t) is not null then execute format('truncate table public.%I', t); end if;
  end loop;
end $$;
delete from auth.users;
