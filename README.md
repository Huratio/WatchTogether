# CoView Android

A Syncplay-style Android watch-together app: everyone watches their own local copy while Supabase Realtime carries playback commands, presence and chat.

## Current release
- CoView branding and custom teal/blue default accent.
- Dark/light themes and a custom accent colour wheel.
- Twenty illustrated profile pictures, random selection, and custom image profile pictures.
- Emoji picker in room chat.
- Player settings for playback speed, audio-track selection, embedded subtitle tracks, CC, subtitle delay, and custom SRT/VTT/SSA/ASS subtitles.
- Common local video formats including MKV, MP4, WebM, MOV, AVI and 3GP are accepted; actual codec support depends on the Android device/Media3.
- Full-screen and CC controls live inside the player controls; fullscreen switches to landscape.
- Join/leave notices with dedicated chimes and a separate incoming-chat sound, including while fullscreen.
- Complete participant roster handshake for late joiners and presence updates.

## Synchronization
The stable synchronization implementation is preserved: play/pause/seek synchronization, stale-event filtering, remote guards, join catch-up, reconnect handling, foreground service and equal participant controls are retained.

## Supabase
Use the Project URL and the public/publishable client key. Never put a `service_role` or `sb_secret_...` key in the APK.

The movie itself is never uploaded.

## Build
GitHub Actions builds `app-debug.apk` from `android/`.


## v1.7.1
- YouTube-style in-player CC and fullscreen controls.
- Separate chat and room notification sounds.
- Reliable room roster re-announcement.
- Simple Leave room action.
- Illustrated anime-style profile collection.
