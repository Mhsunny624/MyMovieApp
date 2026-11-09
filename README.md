# MyMovieApp (personal Android app)

Native Kotlin + Jetpack Compose version of the "library" web app: pick folders, get posters/cast/episodes, resume playback.

## Run it
1. Install Android Studio (latest stable). Open this folder, let Gradle sync (it downloads Gradle 8.9 + dependencies).
2. Plug in a phone (USB debugging) or start an emulator, then press Run.
3. In the app: Profile -> Scan -> paste your free TMDB API key (themoviedb.org -> Settings -> API).
4. Home -> Choose folder... and pick your movies/series folder.

## Features
- Folder scan (persistent access), movie/series detection from file and folder names, season/episode grouping
- TMDB posters, backdrops, overview, cast, episode titles/stills (only title + year are sent; files never leave the device)
- Home (continue watching, recently added), Library (Films/Series, sort, filter), title detail, search across titles/episodes/filenames
- Player (Media3/ExoPlayer): resume, autoplay next episode, sidecar .srt/.vtt, embedded tracks via CC menu, subtitle style settings
- Settings: libraries, unmatched titles + manual match, playback, hidden titles, storage
- Live TV (.m3u from file or URL) and paste-a-link playback (HLS/DASH/progressive)

## Google TV / Android TV
- Package ID: com.mymovieapp.app (permanent once published to Google Play)
- Same APK: it appears in the TV launcher, switches to a top navigation bar, shows focus outlines and works fully with a remote.
- Sources (Profile -> Libraries): **Device storage / USB** (reads what the system has indexed; needs the storage/video permission),
  **Network share (SMB)** (host, share, optional folder, login; password is encrypted with the Android Keystore),
  plus the system folder picker where the device has one.
- A TV and a phone each keep their own library.
- Test with the "Television (1080p)" / Google TV emulator, or `adb install` onto your device.

## Subtitles
- In the player tap the CC button (on TV it's in the control bar): choose an on-screen track, search/download subtitles, shift timing +-0.5 s, and change style live.
- First source: SubDL (free key from subdl.com -> profile -> API). Paste it in Profile -> Playback.
- Downloads are converted to UTF-8 and stored privately per video, so they work for SMB/USB sources too.
- Add another source: implement `SubtitleProvider` in data/Subtitles.kt (search + download) and add it to `SubtitleProviders.all`. Its key box appears in Settings automatically and its results merge into the player's search.

## Live TV
- Live tab: search, filter chips (All, Favourites, Recent, then each group), a LIVE hero card for the last-watched channel, channel numbers, favourites (star).
- Add playlists from the + button or Profile -> Libraries -> Playlists: paste an .m3u link or pick a file, preview channel/group counts, tick the groups to keep, then "Add N channels". Playlists with a link can be refreshed; favourites are kept on refresh.
- The program guide (XMLTV) is not included yet; each channel's `tvg-id` is already stored for it.

## Live TV tips
- Playlists may carry login headers (`|User-Agent=...` suffix or `#EXTVLCOPT` lines); they are applied automatically. Streams default to a VLC-style User-Agent.
- Links with no file extension are probed to detect HLS. If a channel still fails, the player now shows the exact error (for example HTTP 403 = server refused the app).

## Known limits
- AC3/DTS/EAC3 audio may be silent on some phones (hardware decoder dependent). Fix later by adding an FFmpeg audio extension.
- Unmatched titles are retried on every rescan.
- SMB: SMB2/3 only (no old SMB1 shares); you type the share name (no network browsing yet); sidecar subtitles work on SMB and folder-picker sources but not on Device storage / USB.
- If the build fails on `BCSecurityProvider` (SMB crypto), remove `.withSecurityProvider(BCSecurityProvider())` in data/Smb.kt and its import.
