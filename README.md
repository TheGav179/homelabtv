# HomelabTV

An open-source live TV app for Android TV that uses your TV's **built-in tuner** for video and your **homelab server** for guide data — and keeps working when the server is down.

Think of it as a replacement for the manufacturer's TV app (Sony's, in particular), with a Jellyfin-style UI, a real timeline guide, and none of the telemetry.

## Why it's different

Most self-hosted live TV setups (Plex, Jellyfin, Emby, Channels DVR, Tvheadend) put the **server in the video path**: a network tuner feeds the server, the server streams to the app, and if the server is down there's no TV.

HomelabTV splits the jobs:

```
┌─────────────────────────┐        ┌──────────────────────────────┐
│ Android TV               │        │ Homelab server (Docker)      │
│                          │  HTTP  │                              │
│  antenna → TV tuner ─────┼──╳─────┤  XMLTV files (WebGrab+Plus,  │
│  (video never leaves     │ guide  │  zap2xml, ...)               │
│   the TV)                │  data  │  + TMDB artwork cache        │
│                          │  only  │  + channel mapping web UI    │
└─────────────────────────┘        └──────────────────────────────┘
```

- **Video** comes from the TV's own ATSC tuner via Android's TV Input Framework. The server is never in the video path.
- **Guide data** (schedules, posters, backdrops, logos) comes from the server and is cached on the TV. Server offline? Everything keeps working — the guide just shows how old its data is.

## Features

- Jellyfin-style dark UI with backdrop art from TMDB
- Full-screen timeline guide (EPG) with a live mini-player, "now" line, and time-anchored D-pad navigation (long program blocks don't fling your cursor sideways)
- Guide opens on the channel you're watching
- Channel zapper bar with progress and "next up"
- Direct channel number entry from the remote's number pad
- Detailed info banner (Info / Options tabs) with audio track and closed-caption selection
- Offline-first: last guide sync is cached on the TV as JSON
- Channel rescan integration: launch the tuner's scan wizard from Settings; the channel list reloads automatically when you return
- Server web UI for mapping physical channels to XMLTV IDs, uploading channel logos, and overriding names/posters

## Requirements

- **TV**: Android TV with a built-in tuner exposed through the TV Input Framework (tested on Sony Bravia with ATSC; other brands with hardware tuners should work — reports welcome). Streaming boxes without tuners will only show the guide.
- **Server**: anything that runs Docker.
- **Guide source**: one or more XMLTV files (WebGrab+Plus, [zap2xml](https://github.com/jef/zap2xml), or any XMLTV producer). Every `.xml` file in the data directory is parsed.
- Optional: a free [TMDB API key](https://www.themoviedb.org/settings/api) for posters and backdrops.

## Setup

### 1. Backend

```bash
cd backend
# put your XMLTV files in ./data (or edit docker-compose.yml to mount your existing directory)
TMDB_API_KEY=yourkey docker compose up -d
```

Open `http://<server>:30060` and map each XMLTV channel to its physical channel number (e.g. `7.1`). You can also upload channel logos and delete/override mappings there.

Example zap2xml companion container (6-hour refresh, 3 days of data):

```yaml
services:
  zap2xml:
    image: ghcr.io/jef/zap2xml:latest
    environment:
      - POSTAL_CODE=YOUR_ZIP
      - COUNTRY=USA
      - LINEUP_ID=USA-OTA-YOUR_ZIP
      - OUTPUT_FILE=/data/xmltv.xml
      - TIMESPAN=72
      - SLEEP_TIME=21600
    volumes:
      - ./data:/data
    restart: unless-stopped
```

### 2. App

```bash
cd android-app
./gradlew assembleDebug
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch the app asks for the `READ_TV_LISTINGS` permission (required to see the TV's scanned channels), then set your server URL in **Menu (Back button) → Settings**.

## Remote controls

| Key | Action |
|---|---|
| D-pad Up/Down, CH +/- | Change channel |
| Number keys | Direct channel entry (e.g. `7`→`7.1`, `20`+`.3`) |
| OK | Toggle channel info bar |
| OK (long press) | Detailed banner: show info, audio + subtitle tracks |
| GUIDE | Open/close the timeline guide |
| INFO | Info bar with clock; quick second press → detailed banner |
| BACK | Close current overlay / open the side menu |

## Caveats

- Channel-number entry assumes ATSC-style `major.minor` numbering (US-centric; a first digit above 5 is treated as a complete single-digit major). DVB regions may want to adjust `MainActivity.appendDigit`.
- Manufacturer firmware quirks vary. Example: Sony shows its own system InfoBar on the ⓘ key at firmware level, which no app can suppress — that's why everything is also reachable via OK.
- The first guide request after new XMLTV data lands is slow while new titles are enriched against TMDB (cached for 7 days afterward); the app tolerates this with generous timeouts.

## License

[MIT](LICENSE)
