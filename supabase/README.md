# Supabase setup

The Android MVP uses **Supabase Realtime Broadcast + Presence**. It does not need a database table for the live sync/chat MVP.

1. Create a Supabase project.
2. In the Android app, enter the project URL, e.g. `https://YOUR_PROJECT.supabase.co`.
3. Enter the project's **anon/public** key. Never put a service-role key in the app.
4. Realtime is used on a public channel named from the room. Playback events and chat are ephemeral.

For production, add authentication and private Realtime channels/RLS before opening the app to untrusted users.
