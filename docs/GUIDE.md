# User guide — Vibe IPTV

> Back to the [README](../README.en.md) · 🇨🇱 [Español](GUIA.md)

Two native apps that share the same menu design (a grid of rounded tiles with side paging and a bottom bar showing the category and date/time):

| | Web (browser) | Android / Android TV |
|---|---|---|
| Technology | Framework-free HTML/CSS/JS + hls.js + mpegts.js (+ ffmpeg) | Kotlin + Jetpack Compose + ExoPlayer, VLC and MPV |
| M3U / M3U8 lists | URL or file | URL or file |
| Xtream Codes | Live, movies and series (episodes) | Live, movies and series (episodes) |
| XMLTV EPG guide (.xml / .xml.gz) | Yes (now / next + progress) | Yes (now / next + progress) |
| Favorites, search, categories, multiple lists | Yes | Yes |
| Remote control / D-pad | Arrows, Enter, Back, PgUp/PgDn | Android TV launcher, banner, D-pad, CH+/CH−, MENU, INFO |
| Subtitles | Embedded tracks, local file, OpenSubtitles · SubDL · Podnapisi | Embedded tracks, local file, OpenSubtitles · SubDL · Podnapisi, "Open in VLC" |
| Language | Spanish or English (Settings → Appearance → Language) | Spanish or English (Settings → Appearance → Language) |

> The app is only a **player**: it includes no channels. Use it with lists you have legitimate access to.

---

## 1. Web app

**Easiest on Windows:** double-click `web/iniciar.bat` (opens the browser by itself) or `web/iniciar-red-local.bat` to also use it from the TV or phone on your network (it shows the address to use). Keep the black window open while you use the app.

```bash
cd web
node server/server.js          # http://localhost:8080
# or reachable from the TV / phone on your local network:
node server/server.js --lan
```

Requires Node 18+ and has **no required dependencies** (hls.js and mpegts.js are already in `web/vendor/`; ffmpeg is optional).

To leave it installed on a NAS, Raspberry Pi or server, see [Install on your own server](SELF-HOSTING.md).

**Why a server?** Almost no IPTV provider lets a browser read its streams (CORS), and many use `http://`. `server.js` serves the app and acts as a proxy: it rewrites HLS playlists so segments also go through it, follows redirects and sends a player User-Agent. The app first tries a direct connection and, if that fails, uses the proxy automatically (configurable in Settings). Don't expose the server to the Internet without a password (`VIBE_PASSWORD`): it's a proxy meant for your PC or local network.

It can also be installed as an app (PWA) from Chrome on Android: ⋮ menu → "Install app".

**Keys:** arrows to move (← → at the edge change page), Enter to open, Esc/Back to go back, `F` toggles favorite, `/` searches. In the player: ▲/▼ changes channel, ◄/► skips 10 s in movies, Enter shows the controls.

Tests: `npm test` (M3U/XMLTV/SRT parsers, proxy, password and translations).

## 2. Android / Android TV app

1. Open the `android/` folder in **Android Studio** (Meerkat or newer). It will download Gradle 8.13 and the dependencies.
2. Connect a phone or Android TV with USB/ADB debugging and press ▶ *Run*.
3. To build an APK: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*, or `./gradlew assembleRelease` in a terminal. One APK is built per architecture (smaller): most Android TVs and TV boxes use `armeabi-v7a` or `arm64-v8a`; if you're not sure, use `universal` (the release is signed with the debug key so you can test it; change the signing before publishing).

**Without Android Studio (cloud build):** every change on GitHub runs "Compilar APK" in the **Actions** tab (or press *Run workflow*). When it finishes (≈10 min), the APK is under **Releases → test build** and in *Artifacts* (**Vibe-IPTV-APK**). Use the `universal` APK if you don't know your device's architecture. If the build fails, the full log is saved to the `build-logs` branch.

Installing on a phone: copy the APK, open it and allow "Install unknown apps" for your file manager or browser.

**Easiest on a TV:** the *Downloader* app with the code **9370754** (or the link `carmelitasoftworks.github.io/Vibe-IPTV/tv`) (step by step in [DOWNLOADER.en.md](DOWNLOADER.en.md)).

Installing on an Android TV without Android Studio: enable *Developer options → Network debugging* on the TV and run `adb connect TV_IP` and `adb install app-release.apk`. You can also copy the APK with an app such as "Send Files to TV" or "Downloader".

**Android TV:** it shows up in the TV launcher (banner included), needs no touchscreen and everything works with the remote: D-pad on the grid (reaching the edge changes page), long-press OK or the MENU key to mark a favorite, CH+/CH− and ▲/▼ change channel in the player, MENU opens the options (favorite, subtitles, Open in VLC), INFO shows the guide.

**Phone:** the app opens in landscape, like a TV. Swipe the grid to change page and long-press a tile to mark it as a favorite; the player supports picture-in-picture (PiP) when you leave with Home.

## 3. Sections: Live TV, Movies and Series

When a list opens you get **Home** with three big tiles (Live TV, Movies, Series) and how much content each has; the same sections are always available in the top tabs. Back returns to Home.

- **Xtream Codes** already splits the three sections.
- **M3U lists** are sorted automatically: `/movie/` or `/series/` in the URL, video extensions (`.mp4`, `.mkv`…), group names (`Películas`, `Movies`, `VOD`, `Cine`, `Series`, `Novelas`, `Temporada`…) and episode patterns (`S01E02`, `T1E2`, `1x02`). Loose episodes are grouped into their series and sorted by season and episode.

## 4. Program guide (EPG)

- It uses the guide named by the list (`x-tvg-url`), the Xtream one (`xmltv.php`) or the one you enter when editing the list. You can enter **several separated by commas**: they are merged.
- **It updates itself**: when you add a list, every time you open the app, when you return after 30 minutes and every 4 hours while the app is open. While downloading, the last saved guide is shown, and it's kept if the connection fails. In **Settings** you can see when it was updated and use "Update guide now".
- To build your own guide with free software: [iptv-org/epg](https://github.com/iptv-org/epg) (for Chile, `mi.tv`, `gatotv.com` and `directv.com` work well):

  ```bash
  git clone --depth 1 -b master https://github.com/iptv-org/epg.git
  cd epg && npm install
  npm run grab --- --sites=mi.tv --days=2      # creates guide.xml
  ```

  Copy `guide.xml` into `web/` and use `guide.xml` as the guide (web) or `http://YOUR-PC-IP:8080/guide.xml` (Android/TV with the server running on your local network).

## 5. Lists screen and Settings

- **Lists screen**: four big options to add a list (M3U list by link, M3U file, Xtream Codes and from a screenshot) and your saved lists to open them with one click. Open it with the list button (top left).
- **Settings** (gear button), in the style of OwnTV: sections on the left with their number of options and, on the right, each setting with its value; search at the top. With the remote: ▲▼ between sections, ► enters, ◄ goes back.
  - Quick: channel numbers, guide below the tiles, autoplay next episode, animations.
  - Lists & guide · Appearance (**language** Spanish/English; the menu is **white by default**; optional dark theme and accent color; tile size) · Playback · Subtitles · Network (web only) · App startup (last list, lists screen or last channel) · Data · About.

## 6. Player and cable-TV channels (AC-3, MP2, MPEG-2)

**Controls (web):** previous/next, ±10 s, volume, side channel list with the guide (◄ on a live channel), subtitles, audio, quality, aspect ratio (fit, fill, stretch, zoom), speed, technical info (engine, resolution, codecs, buffer), favorite, picture-in-picture, "Open in VLC" and fullscreen. Keys: M mute, I info, L list, +/− volume.

**Cable-TV formats:** many live channels carry **AC-3 / MP2** audio or **interlaced MPEG-2** video, which browsers can't play.
- **Web:** the local server converts them on the fly with **ffmpeg** (only what's needed: H.264 video is copied and audio converted to AAC; MPEG-2 video is deinterlaced and converted to 720p H.264). `iniciar.bat` installs ffmpeg by itself the first time (`npm install`, about 70 MB). In Settings → Playback: *Automatic* (when the channel fails or has no sound), *Always on Live TV* or *Never*, plus the conversion quality.
- **Android / TV:** three engines: **ExoPlayer**, **VLC (LibVLC)** and **MPV (libmpv, the same one OwnTV uses)**. In *Automatic* they're tried in that order: if ExoPlayer can't, it moves to VLC and, if VLC can't either, to MPV (Android 8.0+); the following live channels start with the last engine that worked. In the player menu (MENU key) you can switch engine, aspect ratio and audio track.

## 7. Subtitles from several sources

The search queries several sources **at once** and shows where each result comes from:

| Source | Key | Notes |
|---|---|---|
| OpenSubtitles | Not needed | Uses Stremio's open add-on and Cinemeta to find the title. With your (optional) API key the official API is also queried, the same database as VLC's VLSub. |
| SubDL | Free at subdl.com | Large Latin American Spanish catalog. |
| Podnapisi | Not needed | |

- `.zip` files are extracted automatically, `.ass`/`.ssa` are converted and the encoding is fixed: Spanish `.srt` files in Windows-1252 no longer show `Ã±`.
- **Delay:** if the subtitle is early or late, use "Show earlier / later" in the subtitles menu (0.5 s steps).
- You can also load your own file and choose the tracks embedded in the video.
- On the web the search is done by the local server (that's why it needs `iniciar.bat` or the server); in the preview inside Claude only OpenSubtitles with a key is left.

## 8. Categories and subcategories

Inside Live TV, Movies and Series there's a bar with the list's categories and, if groups have sub-levels (`CHILE | DEPORTES`, `Series » Drama » Turcas`, `LATINO - Noticias`), a second bar with the subcategories. A prefix that only repeats the section ("Movies | Action") is dropped.

## 9. Loading a list from a screenshot

Did your provider send the details over WhatsApp, email or as an image? Use **From a screenshot** on the lists screen (or **📷 Fill in from a screenshot…** inside the form) and pick the image. The app reads the text and fills in whatever it finds:

- **Xtream Codes**: server (also when the port comes separately), username and password, with labels in Spanish, English or Portuguese (`Usuario`, `User`, `Login`, `Contraseña`, `Password`, `Clave`, `Servidor`, `Host`, `DNS`, `URL`, `Port`…), on the same line or the next one.
- **Full links**: a `get.php?username=…&password=…` link brings everything at once; `.m3u`/`.m3u8` links and the `xmltv.php` guide are recognized too.
- Filled fields are highlighted and the password is shown so you can check it: reading can mix up similar characters (`l`, `I` and `1`, `O` and `0`). **Nothing is saved until you press Save and open.**
- On the web you can also **paste** the screenshot with Ctrl+V on the lists screen.
- **Privacy**: text is read on your device. On Android with ML Kit (the model ships inside the app and works offline). On the web with Tesseract.js: if you open the app with `iniciar.bat` or your server, the reader is installed there; otherwise it's downloaded from jsDelivr the first time. The image is never sent to any server.
- Tip: crop the image so only the details show, and avoid blurry photos or glare.

## 10. Grid guide and catch-up TV (Android)

In **Live TV**, the **📅 Guide** button on the category bar opens the grid: channels on the left and shows on a timeline (from 2 hours ago to 12 hours ahead), with a line marking the current time. It shows the channels of the selected category.

- ▲▼ changes channel, ◄► moves through shows, and the top shows the title, time and description.
- **OK on the show on air** opens the channel live.
- **Shows already aired with ⏪:** if your Xtream account has catch-up for that channel, you can watch them **from the start**. Channels with archive show "⏪ N d" (days available).

## 11. Parental control, Continue watching and backup

- **Parental control** (Settings → Parental control): with a PIN, adult categories (XXX, Adults, Erotic, 18+…) are hidden. Show them with **🔒 Adults** on the category bar by entering the PIN, until the app closes.
- **Continue watching:** movies and episodes resume where you left off. Posters show a progress bar and there's a **▶ Continue watching** filter in Movies and Series.
- **Backup** (Android, Settings → Data): **Export backup** saves lists (with passwords), favorites, Continue watching and settings to a `.json` file; **Import backup** restores them on another device. Keep it somewhere private.

## 12. Language

The app is available in **Spanish** and **English**. By default it follows the system language (Spanish if the device is in Spanish, otherwise English) and it can be set in **Settings → Appearance → Language**. Translations live in `i18n/en.json`, a single dictionary for both the web app and Android: to fix or add a text, edit it there and run `node i18n/build.mjs`.

## Preview version (artifact)

There's a preview published inside Claude with demo content (6 channels, 3 movies and 1 series). That environment blocks connections to external servers, so it's good for trying the menu, navigation and player, but not real lists.

## Layout

```
web/
  index.html, css/styles.css, sw.js, manifest.webmanifest
  js/app.js        UI, grid, panels, player, keyboard/remote
  js/m3u.js        M3U parser
  js/xtream.js     Xtream Codes client
  js/epg.js        XMLTV parser
  js/player.js     hls.js / mpegts.js / native, proxy retry
  js/subtitles.js  multi-source subtitles, SRT→VTT
  js/nav.js        spatial arrow navigation
  js/i18n.js       English translation
  js/ocr.js, js/ocr-parse.js   screenshot reading and detail recognition
  server/          server + proxy + subtitles + ffmpeg conversion
  Dockerfile, docker-compose.yml, deploy/   self-hosting
i18n/en.json       Spanish → English dictionary (web and Android)
android/
  app/src/main/java/cl/iptv/player/
    MainActivity.kt          remote keys, PiP, TV detection
    data/                    models, M3U, Xtream, EPG, subtitles, storage, screenshots (ScreenshotParser/Reader)
    ui/SplashScreen.kt       splash screen
    ui/MenuScreen.kt         tile grid + bottom bar
    ui/PlayerScreen.kt       player, guide, options, subtitles
    ui/VlcEngine.kt, ui/MpvEngine.kt   VLC and MPV engines
    ui/SettingsScreen.kt     settings
    ui/I18n.kt               English translation
```
