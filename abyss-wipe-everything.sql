-- Abyss: WIPE EVERYTHING. Deletes every account (emails included) and all saved data.
-- This cannot be undone.
-- Before running: Storage > each bucket (avatars, covers, songs) > "..." > Empty bucket.
truncate table public.user_data, public.profiles, public.connect_devices restart identity;
truncate table public.cover_picks;
delete from public.auth_handoff;
delete from auth.users;
