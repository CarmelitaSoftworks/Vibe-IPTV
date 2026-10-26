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

**Download:** on GitHub, green button **Code → Download ZIP** and unzip it (or `git clone`). The `.bat` files are inside the `web` folder. You need **Node.js** (LTS version, from [nodejs.org](https://nodejs.org)).

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

**iPhone and iPad:** with the server running on your local network (`iniciar-red-local.bat`, `--lan` or Docker), open the address it shows in **Safari** (for example `http://192.168.1.20:8080`) and tap **Share → Add to Home Screen**: it opens full screen with its own icon. Safari on iPhone can't play MPEG-TS, so the app uses the channel's `.m3u8` version or, if there isn't one, the server converts it to HLS with ffmpeg (*Preparing the channel for iPhone…*). Picture-in-picture and AirPlay work. If the video starts muted, tap the screen once to turn the sound on (an iOS rule). If Windows asks about the firewall, allow access on private networks.

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

**Phone:** the app opens in landscape, like a TV. Swipe the grid to change page and long-press a tile to mark it as a favorite. In the player, the **⧉ Mini player** button keeps the video in a small window while you use the menu, and if you leave the app **while something is playing** it switches to the system picture-in-picture (PiP) by itself (not when paused).

## 3. Sections: Live TV, Movies and Series

When a list opens you get **Home** with three big tiles (Live TV, Movies, Series) and how much content each has; the same sections are always available in the top tabs. Back returns to Home.

- **Xtream Codes** already splits the three sections.
- **M3U lists** are sorted automatically: `/movie/` or `/series/` in the URL, video extensions (`.mp4`, `.mkv`…), group names (`Películas`, `Movies`, `VOD`, `Cine`, `Series`, `Novelas`, `Temporada`…) and episode patterns (`S01E02`, `T1E2`, `1x02`). Loose episodes are grouped into their series and sorted by season and episode.

## 4. Program guide (EPG)

- It uses the guide named by the list (`x-tvg-url`), the Xtream one (`xmltv.php`) or the one you enter when editing the list. You can enter **several separated by commas**: they are merged.
- **It updates itself**: when you add a list, every time you open the app, when you return after 30 minutes and every 4 hours while the app is open. While downloading, the last saved guide is shown, and it's kept if the connection fails. In **Settings** you can see when it was updated and use "Update guide now".
- **Lighter downloads and fast opening (Android):** the guide is only downloaded again if it changed on the server (otherwise the saved one is kept) and it retries by itself if the server fails. The guide already read is saved in a compact format, so when you open the app it appears in a couple of seconds even if the XML weighs tens of megabytes.
- **More channels with a guide:** channels are matched to their guide even if the name includes the country (*CL: Canal 13*), the quality (*HD*, *FHD*, *4K*), accents or different capitalization. On Xtream accounts, if a channel isn't in the XMLTV guide, the app asks the provider for its listings when you watch it or move over it in the Guide.
- **More hours:** 24 h back (to watch today's shows on catch-up) and 48 h ahead (8 and 30 in light mode).
- To build your own guide with free software: [iptv-org/epg](https://github.com/iptv-org/epg) (for Chile, `mi.tv`, `gatotv.com` and `directv.com` work well):

  ```bash
  git clone --depth 1 -b master https://github.com/iptv-org/epg.git
  cd epg && npm install
  npm run grab --- --sites=mi.tv --days=2      # creates guide.xml
  ```

  Copy `guide.xml` into `web/` and use `guide.xml` as the guide (web) or `http://YOUR-PC-IP:8080/guide.xml` (Android/TV with the server running on your local network).

## 5. Lists screen and Settings

- **First launch:** the first time, with no saved lists, a wizard appears (design inspired by OwnTV): *Welcome → Before you start → Add your list*, with **Type it here** (M3U link, file or Xtream Codes), **From a screenshot** or **Restore a backup**; you can also *Skip for now*.
- **List saved on the device (Android):** the processed list is stored, so the app opens **instantly** and works even if the server doesn't answer (it warns and shows the saved one). It updates itself in the background; to download it again: Settings → Lists & guide → **Update list now**. There, **Account** shows an Xtream account's expiry and connections (it warns if it expires within 7 days or all connections are in use).
- **Lists screen**: four big options to add a list (M3U list by link, M3U file, Xtream Codes and from a screenshot) and your saved lists to open them with one click. Open it with the list button (top left).
- **Settings** (gear button), in the style of OwnTV: sections on the left with their number of options and, on the right, each setting with its value; search at the top. With the remote: ▲▼ between sections, ► enters, ◄ goes back.
  - Quick: channel numbers, guide below the tiles, autoplay next episode, animations.
  - Lists & guide · Appearance (**language** Spanish/English; the menu is **white by default**; optional dark theme and accent color; tile size) · Playback · Subtitles · Network (web only) · App startup (last list, lists screen or last channel) · Data · About.

## 6. Player and cable-TV channels (AC-3, MP2, MPEG-2)

**Controls (web):** previous/next, ±10 s, volume, side channel list with the guide (◄ on a live channel), subtitles, audio, quality, aspect ratio (fit, fill, stretch, zoom), speed, technical info (engine, resolution, codecs, buffer), favorite, picture-in-picture, "Open in VLC" and fullscreen. Keys: M mute, I info, L list, +/− volume.

**Cable-TV formats:** many live channels carry **AC-3 / MP2** audio or **interlaced MPEG-2** video, which browsers can't play.
- **Web:** the local server converts them on the fly with **ffmpeg** (only what's needed: H.264 video is copied and audio converted to AAC; MPEG-2 video is deinterlaced and converted to 720p H.264). `iniciar.bat` installs ffmpeg by itself the first time (`npm install`, about 70 MB). In Settings → Playback: *Automatic* (when the channel fails or has no sound), *Always on Live TV* or *Never*, plus the conversion quality.
- **Android / TV:** four engines: **ExoPlayer**, **VLC (LibVLC)**, **MPV (libmpv, the same one OwnTV uses)** and the **system engine** (Android's built-in player, which uses the device chip). In *Automatic* every channel starts with **ExoPlayer** and only moves to VLC, then MPV (Android 8.0+) and finally the system one (which on live channels reconnects on its own up to 5 times if the signal drops, and shows its loading waits and reconnections in *Technical information*), if that channel fails. ExoPlayer also brings FFmpeg for video: if the device can't decode a video (for example cable-TV MPEG-2), it does it in software without switching engine. The device chip's decoder is always preferred (lighter); software stays as a fallback, and *Technical information* shows which one is in use. **Tunnel mode (TV)** in Settings → Playback (off): video and audio go straight to the TV chip; if you see a black picture or dropouts, turn it off. In the player menu (MENU key) you can switch engine, aspect ratio and audio track.

**Controls (Android):** only the most used ones are visible — channel list, audio, subtitles, favorite, mini player and **⋯ More options**. Inside *More options* (with icons): quality, aspect ratio, speed, previous channel, record, multiview, picture-in-picture, sleep timer, engine, technical info and open in VLC. Panels show the full text and mark the chosen option with ✓.

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
- On the web the search is done by the local server (that's why it needs `iniciar.bat` or the server); in the online preview only OpenSubtitles with a key is left.

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

## 10. Grid guide and catch-up TV

In **Live TV**, the **📅 Guide** button on the category bar opens the grid: channels on the left and shows on a timeline (from 2 hours ago to 12 hours ahead), with a line marking the current time. It shows the channels of the selected category.

- ▲▼ changes channel, ◄► moves through shows, and the top shows the title, time and description.
- **OK on the show on air** opens the channel live.
- **Shows already aired with ⏪:** if your Xtream account has catch-up for that channel, you can watch them **from the start**. Channels with archive show "⏪ N d" (days available).

## 11. Parental control, Continue watching and backup

- **Parental control** (Settings → Parental control): with a PIN, adult categories (XXX, Adults, Erotic, 18+…) are hidden. Show them with **🔒 Adults** on the category bar by entering the PIN, until the app closes.
- **Continue watching:** movies and episodes resume where you left off. Posters show a progress bar and there's a **▶ Continue watching** filter in Movies and Series.
- **Backup** (Android, Settings → Data): **Export backup** saves lists (with passwords), favorites, Continue watching and settings to a `.json` file; **Import backup** restores them on another device. Keep it somewhere private.

## 12. Recording, profiles and controls size

- **Recording (Android):** on a live channel, the player's **●** button records to *Downloads/VibeIPTV* (`.ts` file, opens with VLC). While recording you see "● REC" with time and size. It uses a separate connection: if your account allows only one, the picture may drop. Keep the app open while recording.
- **Profiles (Android):** on the lists screen (👤 button) or **Settings → Parental control → Profile**. Each profile has its own favorites and "Continue watching". A **kids** profile never shows adult content and, with a PIN, leaving it asks for the PIN.
- **Player controls size:** **Settings → Playback**, 70 %–150 % on Android and 80 %–140 % on the web. Handy on large TVs or phones.
- **Channel list in the player:** shows logo, name (no number) and the current show with its progress.
- **Brightness and volume without leaving the video (Android, touchscreen):** swipe up or down on the **left edge** for brightness and on the **right edge** for volume. An indicator shows while you adjust; brightness returns to the system level when you leave the player.
- **If the picture freezes:** the player detects it (the position stops moving without you pausing), reconnects the channel and, if it stops again, tries another engine (in automatic). If nothing works it shows the notice with **Retry**.

## 13. Language

The app is available in **Spanish** and **English**. By default it follows the system language (Spanish if the device is in Spanish, otherwise English) and it can be set in **Settings → Appearance → Language**. Translations live in `i18n/en.json`, a single dictionary for both the web app and Android: to fix or add a text, edit it there and run `node i18n/build.mjs`.

## 14. Remote control, multiview and more

- **Number keys:** in the player, type the channel number (shown top right) and after 1.5 s it jumps there. If the list has no numbers, the position is used.
- **Previous channel:** the remote's *LAST / Back channel* key, or the ↺ player button (on the web, the **P** key).
- **Sleep timer:** the ☾ player button: 15, 30, 60, 90 or 120 min. It warns a minute before and closes the player.
- **Double tap (phones):** left skips back 10 s, right skips forward 10 s in movies and series; the centre pauses.
- **Recently watched:** the 🕘 pill in the category bar.
- **Organize categories:** *Categories* button → ✎ *Organize categories*: ▲▼ to reorder and *Hide* to remove them from the bar (and from “All”). Saved per list and per profile.
- **Search the guide:** in the grid guide, 🔍 *Search programme* searches every channel by name; OK opens it live, as catch-up, or shows when it starts.
- **Mini player (TV and TV boxes):** the player's *Picture in picture* button leaves the video in a small corner window and returns to the menu so you can browse other listings or the guide. The video keeps playing; the remote's *Channel +/−* and *Play/Pause* still work. To go back to full screen: *Back* on Home, or tap the window. Picking another channel opens it full screen. Phones work the same way (⧉ button visible; the small window's ✕ closes the video); the system PiP is used when you leave the app. **Size and position:** Settings → Playback → *Mini player: size* (small, medium or large) and *position* (any of the four corners). On phones and tablets you can also drag it with your finger (it snaps to the nearest corner) and the ⤡ button changes its size.
- **Multiview:** the ⊞ player button (live TV): up to 4 channels at once. OK picks which one plays sound, OK again opens it full screen, *Menu* (or long press) changes that tile's channel.
- **Android TV / Google TV home screen:** the app publishes a “Vibe IPTV” row with your favorites and recent channels; picking one opens it directly. (Fire TV doesn't support this.)
- **Send log:** Settings → About → *Send log* or *Save log to a file*. It includes recent errors and engine switches, the **last unexpected close** (also in About) and the exit reasons Android 11+ reports; addresses are stored without username or password.
- **Safe mode:** if the app closes twice in a row on launch, the next start doesn't load the last list or the guide and shows a notice, so you never have to clear the app's data. What happened is in *Send log*.
- **Detected device and lite mode:** the first time the app opens it detects the device (memory, 32/64-bit, MPEG-2 / HEVC / AC-3 decoders) and sets lite mode, buffer and animations. See it and run it again in Settings → About → *Detect again and configure*; lite mode can also be forced in Settings → Appearance.
- **Continue watching:** a row on Home with what you left half-watched; also in the *Categories* button and as a pill in Movies and Series.
- **Buffer:** Settings → Playback → *Buffer*. *Fast* changes channels quicker, *Balanced* is recommended and *Stable* keeps more video ahead for providers that drop out. If a live channel stalls 3 times, the app raises its buffer (up to two levels) and remembers it; clear it with Settings → Data → *Forget learned buffer*.
- **Live cushion:** Settings → Playback → *Live cushion* (on). A live channel arrives in real time, so the video reserve does not grow by itself and every dropout uses it up. When it runs low, the channel plays 2–4 % slower (unnoticeable: the audio keeps its pitch) until it is rebuilt, then returns to normal speed. It copes better with later dropouts, at the cost of being a few seconds behind live. Also, if the connection drops for a moment, the app reconnects right away without emptying what was already loaded.
- **Playback diagnostics:** in the player, *More → Technical information* shows, updated every second, the saved reserve, the buffer level (and whether it was learned), what the live cushion is doing, the channel's stalls and reconnections and the download speed. If a channel misbehaves, a photo of that screen helps find out why.
- **Automatic subtitles:** Settings → Subtitles (on by default). When a movie or episode starts, if the audio isn't in your first preferred language, it uses the built-in track in that language or searches OpenSubtitles and Podnapisi (no key) and loads the best result. Change or remove it from the player's Subtitles button.
- **AI subtitles (Android):** in the player, *Subtitles → ✨ Generate with AI* and pick the audio language. Audio is transcribed on the device with Vosk, offline and without sending anything. It uses the ExoPlayer engine. The **AI** build (`vibe-iptv-ia-armv7.apk` for TV, `vibe-iptv-ia-arm64.apk` for phones) includes the Spanish model; the regular one downloads it the first time (~40 MB). It doesn't translate: it writes what is said in the audio's language. It evens out the voice volume, shows at most two lines and says *✨ Listening…* or *No audio to recognise* when there's no text.
- **AI subtitles, improvements:** before recognition, the audio goes through a voice filter (it removes the bass of music and effects) and automatic volume control. Once on, they stay on for every channel, also after reopening the app, until you turn them off in the player or in Settings → Subtitles → *AI subtitles*. Since ExoPlayer now also decodes video with FFmpeg, you rarely need to switch engine to use them.
- **Themes:** Settings → Appearance → *Theme*: Light, Dark, Tiles, Waves or Custom. In Custom you choose base, background and accent colours (hex), background style, background image, translucent tiles and square corners; *Export theme* saves it to a .json file (with the image) to use on another device or share, and *Import theme* loads it. In **Waves** on a touch screen: swipe up/down to move through the list, sideways to change column (or go back), and a tap opens.
- **Pulse, Horizon and Desktop themes (Android):** in Settings → Appearance → *Theme*. **Pulse** shows a row of icons at the top: the chosen one grows, shows its name beside it and its background image, with its buttons below (with touch, the first tap selects and the second opens); on Home, the *Continue watching* cards (or your favorites) are at the bottom. **Horizon** shows the focused item large on Home and rows of tiles with a white border; in TV, Movies and Series, a grid with *Categories*, *Favorites* or *Continue watching* and *Search*. **Desktop** is a blue window with desktop icons on the left (when there is room), the side panel with the current section's **categories**, a taskbar and a *start* menu. All three scale up in proportion on tablets, laptops and monitors, and have a compact view on phones.
- **Scheduled recording:** in the guide, pick a show that hasn't started and press *● Schedule recording*. It records at air time even with the app closed (starts 2 min early, ends 3 min late) and is saved in *Downloads/VibeIPTV*. At the top of the guide, *● Scheduled* lists them so you can cancel. The TV must stay on or in standby (not unplugged), and if your account allows a single connection you can't watch another channel while recording.
- **Pause and rewind live TV:** Settings → Playback → *Pause and rewind live TV* (15 minutes by default; also 10 or 30, or off). While you watch a .ts channel with ExoPlayer, the app saves it on the device and plays from there: with the controls hidden, **◄** rewinds 10 s (several presses add up) and **►** goes forward; ⏯ pauses without cutting anything. When you're behind live, the top right shows how much (*−1:20*) and the bar shows the red *Back to live* button. It uses device storage (about 30 MB per minute in HD) and always leaves 400 MB free; it's deleted when you change channel. *More → Technical info* shows how much is saved. It doesn't work with VLC, MPV or the system engine, or with HLS (.m3u8) channels.
- **Sound:** Settings → Sound, or in the player *More → Sound*. Equalizer: *Clear voices* (sharper dialogue), *More bass* and *Night* (soft bass and everything at an even volume). **Even volume** evens out the volume between channels and ads: it gradually raises what's quiet and lowers what's loud. Works with ExoPlayer, VLC (even volume applies when you reopen the channel) and MPV; with the system engine only the equalizer. It doesn't apply when AC-3 audio goes straight to a sound system.
- **Sync subtitles:** *More → Sync subtitles*: from −5 to +5 s, in half-second steps (*earlier* or *later*). With VLC and MPV on any subtitle; with ExoPlayer, downloaded or file subtitles move either way, and the channel's own subtitles can only be delayed (to show them earlier, switch to VLC).
- **Skip intro (series):** at the start of each episode *Skip intro* appears (with the remote, OK). The first time it jumps 85 s; to make it exact, when the intro ends choose *More → Intro ends here* and the other episodes of that series will jump right there. With Settings → Playback → *Skip the intro automatically* it does it without asking.
- **Recent channels:** in the live TV player, the clock button shows the last 5 channels you watched, with what's on; pick one to go straight there.
- **Send to TV:** with Vibe IPTV open on the phone and the TV, on the same Wi-Fi network, on the phone *More → Send to TV* lists the TVs found: pick one and the channel or movie plays there (movies continue from the same minute). On the TV it can be turned off in Settings → Playback → *Receive videos from the phone*. It only works inside your home network.
- **Automatic quality:** Settings → Playback → *Quality on channels with several qualities*. *Automatic* goes up or down with your internet speed (it remembers the last measured speed to start well) and tells you when it changes (*Quality: 720p (your internet is slower)*). *Data saver* stays at 720p or below and *Always the best* uses the highest. Also on the web.
- **Cable TV with no decoder on the device:** Settings → Playback. If the chip can't handle MPEG-2 video, the automatic engine switches to VLC, which shows the picture without combing; with *Stay on ExoPlayer* the live cushion is kept, but the picture may show combing on motion.
- **Themes on the web:** Pulse, Horizon and Desktop are also in the web app (Settings → Appearance → Theme).
- **SubDL:** paste your free key in Settings → Subtitles and use *Test SubDL key*. Searches use the title's IMDb ID and, for series, download only the episode even when the subtitle comes in a season pack.

## Preview version (artifact)

There's an online preview with demo content (6 channels, 3 movies and 1 series). That environment blocks connections to external servers, so it's good for trying the menu, navigation and player, but not real lists.

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
    ui/SystemEngine.kt                 system engine (Android MediaPlayer)
    ui/SettingsScreen.kt     settings
    ui/I18n.kt               English translation
```
