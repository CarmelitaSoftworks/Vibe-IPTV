<div align="center">

<img src="docs/img/logo.png" width="220" alt="Vibe IPTV · by Carmelita Softworks">

# Credits

🇨🇱 [Español](CREDITOS.md) · 🇬🇧 **English**

</div>

**Vibe IPTV** is developed and maintained by **[Carmelita Softworks](https://github.com/CarmelitaSoftworks)**. 🇨🇱 The Vibe IPTV logo belongs to Carmelita Softworks.

## Made with vibe coding

Vibe IPTV is a ***vibe coded*** project: the idea, design, decisions and testing on real devices are Carmelita Softworks', and the code was written in conversation with **Claude**, the AI assistant by **[Anthropic](https://www.anthropic.com)**. Every release is built and passes automated tests before it is published.

## Open-source projects

It also exists thanks to these projects and services:

### Playback

| Project | What it is used for | License |
|---|---|---|
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Main playback engine | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |
| [LibVLC (VideoLAN)](https://code.videolan.org/videolan/vlc) | Fallback engine when a channel fails | ![LGPL 2.1+](https://img.shields.io/badge/license-LGPL%202.1%2B-8250df) |
| [mpv / libmpv para Android](https://github.com/jarnedemeulemeester/libmpv-android) | Second fallback engine | ![see repository](https://img.shields.io/badge/license-see%20repository-6e7781) |
| [FFmpeg](https://ffmpeg.org) | Audio and video decoding | ![LGPL 2.1+](https://img.shields.io/badge/license-LGPL%202.1%2B-8250df) |
| [Decodificador FFmpeg de Jellyfin](https://github.com/jellyfin/jellyfin-androidx-media) | AC-3, E-AC-3 and DTS audio in ExoPlayer | ![see repository](https://img.shields.io/badge/license-see%20repository-6e7781) |
| [NextLib (FFmpeg for Media3)](https://github.com/anilbeesetti/nextlib) | MPEG-2 and other video in ExoPlayer without switching engine | ![GPL 3.0](https://img.shields.io/badge/license-GPL%203.0-blue) |
| [hls.js](https://github.com/video-dev/hls.js) | HLS playback in the web version | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |
| [mpegts.js](https://github.com/xqq/mpegts.js) | MPEG-TS playback in the web version | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |

### Subtitles and AI

| Project | What it is used for | License |
|---|---|---|
| [Vosk (Alpha Cephei)](https://alphacephei.com/vosk) | Offline AI subtitles | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |
| [JNA](https://github.com/java-native-access/jna) | Native bridge used by Vosk | ![LGPL 2.1 / Apache 2.0](https://img.shields.io/badge/license-LGPL%202.1%20%2F%20Apache%202.0-8250df) |
| [ML Kit (Google)](https://developers.google.com/ml-kit) | Read details from a screenshot (Android) | ![Google terms](https://img.shields.io/badge/license-Google%20terms-6e7781) |
| [Tesseract.js](https://github.com/naptha/tesseract.js) | Read details from a screenshot (web) | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |
| [OpenSubtitles · SubDL · Podnapisi](https://www.opensubtitles.org) | Online subtitle search | ![online services](https://img.shields.io/badge/license-online%20services-6e7781) |

### Guide and playlists

| Project | What it is used for | License |
|---|---|---|
| [iptv-org/epg](https://github.com/iptv-org/epg) | Generating XMLTV guides | ![see repository](https://img.shields.io/badge/license-see%20repository-6e7781) |
| [OkHttp](https://github.com/square/okhttp) | Downloads of playlists, guides and models | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |

### Interface

| Project | What it is used for | License |
|---|---|---|
| [Kotlin](https://kotlinlang.org) | Android app language | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |
| [Jetpack Compose](https://developer.android.com/compose) | App screens | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Background work | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |
| [Coil](https://github.com/coil-kt/coil) | Channel logos and posters | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |
| [OwnTV](https://github.com/ahXN00/OwnTV) | Inspiration for the settings and first-run wizard design | ![GPLv3](https://img.shields.io/badge/license-GPLv3-bf3989) |

### Testing

| Project | What it is used for | License |
|---|---|---|
| [Robolectric](https://github.com/robolectric/robolectric) | Automated tests before every release | ![MIT](https://img.shields.io/badge/license-MIT-2ea44f) |
| [Roborazzi](https://github.com/takahirom/roborazzi) | Automatic theme screenshots | ![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-1f6feb) |

Names and trademarks mentioned belong to their respective owners; this project is not affiliated with them. The licenses shown are a summary: each project's own license is the one that applies.
