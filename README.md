# HomelabTV

An open-source live TV app for Android TV that uses your TV's **built-in tuner** for video and your **homelab server** for guide data — and keeps working when the server is down.

Think of it as a replacement for the manufacturer's TV app (Sony's, in particular), with a Jellyfin-style UI, a real timeline guide, and none of the telemetry.

## Why it's different

Most self-hosted live TV setups (Plex, Jellyfin, Emby, Channels DVR, Tvheadend) put the **server in the video path**: a network tuner feeds the server, the server streams to the app, and if the server is down there's no TV.

HomelabTV splits the jobs:

```
┌─────────────────────────┐          ┌──────────────────────────────┐
│       Android TV        │          │ Homelab server (Docker)      │
│                         │   HTTP   │                              │
│  Antenna → TV tuner ────┼────╳─────┤  XMLTV files (WebGrab+Plus,  │
│  (video never leaves    │   guide  │  zap2xml, ...)               │
│   the TV)               │   data   │  + TMDB artwork cache        │
│                         │   only   │  + channel mapping web UI    │
└─────────────────────────┘          └──────────────────────────────┘
```

- **Video** comes from the TV's own ATSC tuner via Android's TV Input Framework. The server is never in the video path.
- **Guide data** (schedules, posters, backdrops, logos) comes from the server and is cached on the TV. Server offline? Everything keeps working — the guide just shows how old its data is.

## Built for WAN-blocked TVs

This app assumes a mindset where the smart TV **never touches the internet**. A common way to get there: give the TV a static IP with a bogus (or empty) gateway, or block its MAC at the router — it can still reach everything on your LAN, but nothing beyond it. Telemetry, ads, and forced updates die at the router.

HomelabTV is proof that the TV doesn't need WAN for any of this: live video comes off the antenna, and everything that *does* need the internet — XMLTV scraping, TMDB artwork — happens **server-side**. The TV only ever talks to your server over plain HTTP on your LAN, and thanks to the offline-first cache it doesn't even need *that* to keep working.

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

> **Tip:** if a guide scraper starts getting IP-blocked, route just that container through a VPN with [gluetun](https://github.com/qmcgaw/gluetun) (`network_mode: "service:gluetun"` on the scraper). The HomelabTV backend itself has no reason to go through it — only the scrapers talk to the outside world.

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

- Channel-number entry uses ATSC-style `major.minor` numbering and defaults to **leading-zero** entry: majors are two digits, so channel 6.1 is typed `0` `6` (shown as `06.`) and 61.1 is `6` `1`. **Settings → Number Entry** switches to a quick mode where a first digit above 5 completes the major by itself (`7` → `7.x`) — convenient in markets with no two-digit majors starting with 6–9, but it makes channels like 61.1 unreachable by direct entry. In either mode, typing a partial number and pausing for 2 seconds commits what you typed (so plain `6` still reaches 6.1).
- Manufacturer firmware quirks vary. Example: Sony shows its own system InfoBar on the ⓘ key at firmware level, which no app can suppress — that's why everything is also reachable via OK.
- The first guide request after new XMLTV data lands is slow while new titles are enriched against TMDB (cached for 7 days afterward); the app tolerates this with generous timeouts.

## License

[MIT](LICENSE)
