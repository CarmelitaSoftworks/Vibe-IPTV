<div align="center">

# 📺 Vibe IPTV

**Tu propio reproductor IPTV, libre y de código abierto, para navegador, Android y Android TV.**

Listas M3U/M3U8 · Xtream Codes · Guía XMLTV · Pensado para el control remoto

![Plataformas](https://img.shields.io/badge/plataformas-Web%20%7C%20Android%20%7C%20Android%20TV-3ddc84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7f52ff?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285f4?logo=jetpackcompose&logoColor=white)
![Motores](https://img.shields.io/badge/motores-ExoPlayer%20%7C%20VLC%20%7C%20MPV-ff7a00)
![PWA](https://img.shields.io/badge/web-PWA-5a0fc8)
![Licencia](https://img.shields.io/badge/licencia-GPLv3-blue)
![Código abierto](https://img.shields.io/badge/c%C3%B3digo-abierto-brightgreen)
![Compilación](https://github.com/CarmelitaSoftworks/Vibe-IPTV/actions/workflows/compilar-apk.yml/badge.svg)

</div>

---

**Vibe IPTV** es un proyecto de **Carmelita Softworks**, **libre y de código abierto** (licencia **GPLv3**) con dos apps
nativas que comparten la misma idea: una **app web instalable (PWA)** que funciona en cualquier
navegador, y una **app nativa para Android y Android TV** escrita en Kotlin con Jetpack Compose.
Tiene un menú de canales en mosaico, ajustes ordenados por secciones y **tres motores de
reproducción** (ExoPlayer, VLC y MPV) para que los canales difíciles de la TV de cable también se vean.

Todo el código está a la vista: puedes revisarlo, compilarlo tú mismo, modificarlo y compartirlo.

> [!IMPORTANT]
> **Es solo un reproductor.** No incluye, vende ni recomienda canales, listas ni transmisiones.
> Tú agregas tu propia lista M3U o tu cuenta Xtream Codes de un proveedor legítimo.

📖 **¿Primera vez?** La [Guía de uso](docs/GUIA.md) explica paso a paso cómo instalar, agregar listas, configurar la guía y usar los subtítulos.

🖥️ **¿Tienes un servidor propio?** (NAS, Raspberry Pi, mini PC, VPS, Tvheadend…) Sigue la guía [Instalar en tu propio servidor](docs/SERVIDOR-PROPIO.md), con Docker, servicio de Linux o Windows.

---

## ✨ Funciones

### ▶️ Reproducción
- **Tres motores en Android** que se relevan solos: ExoPlayer (Media3) → **VLC** (LibVLC) → **MPV** (libmpv). Si uno no puede con un canal, prueba el siguiente; también puedes elegir uno fijo en Ajustes.
- **Formatos de la TV de cable**: video MPEG-2 entrelazado y audio AC-3 / E-AC-3 / MP2, con desentrelazado automático.
- **En el navegador**: hls.js y mpegts.js, y si el navegador no soporta el formato, conversión al vuelo con **ffmpeg** a través del servidor local.
- **Canales en vivo estables**: sin saltos hacia adelante ni parpadeos, con reconexión automática si la señal se corta.
- Menú del reproductor con pistas de audio, calidad, relación de aspecto, velocidad, información técnica del canal, favoritos, imagen en imagen (PiP) y lista de canales sin salir del video.

### 🧭 Navegación
- **Menú en mosaico** grande y claro, cómodo de usar con el control remoto (D-pad) en Android TV.
- Secciones **TV en vivo**, **Películas** y **Series**, con **categorías y subcategorías** tomadas de tu lista.
- Series agrupadas por temporada y episodio.
- **Búsqueda** y **favoritos**.
- Pantalla de inicio animada con el estilo del menú y pantalla de bienvenida para agregar tu primera lista en segundos.
- En Android se abre **en horizontal** por defecto, como un TV.

### 🗂️ Listas y guía (EPG)
- **Listas M3U / M3U8** desde URL o archivo, y **cuentas Xtream Codes** (con detección automática de puertos y formatos).
- **Varias listas guardadas** y cambio rápido entre ellas.
- **Guía de programación XMLTV** (también comprimida en .gz) que se **actualiza sola** al agregar una lista y al abrir la app. Admite varias guías combinadas y ajuste de horario.
- Compatible con guías generadas con el proyecto libre [iptv-org/epg](https://github.com/iptv-org/epg).

### 💬 Subtítulos
- Búsqueda en **varias fuentes a la vez**: OpenSubtitles (la misma base que el complemento **VLSub** de VLC), SubDL y Podnapisi.
- Abre archivos `.zip`, corrige acentos de los `.srt` en español (Windows-1252), convierte `.ass` y permite **adelantar o atrasar** el subtítulo.

### 🎨 Apariencia y ajustes
- Ajustes por secciones (rápidos, listas y guía, apariencia, reproducción, subtítulos, red, inicio, datos).
- Tema claro (predeterminado), oscuro o del sistema, con colores de acento.
- Al abrir la app puede volver directamente al último canal visto.

### 🔒 Privacidad
- **Sin cuentas, sin anuncios y sin telemetría.** Tus listas, favoritos y ajustes se guardan solo en tu equipo.
- Las contraseñas de Xtream se ocultan en mensajes de error y registros.

---

## 🖼️ Capturas

> _Próximamente: inicio, menú de canales, reproductor y ajustes._

---

## 📥 Instalación

### Android / Android TV
1. Ve a la pestaña **[Actions](../../actions)** (o a **[Releases](../../releases)** cuando haya versiones publicadas).
2. Abre la última compilación correcta y descarga **Vibe-IPTV-APK**.
3. Elige el APK según tu equipo:

   | Archivo | Para |
   |---|---|
   | `arm64-v8a` | La mayoría de celulares y TV Box actuales |
   | `armeabi-v7a` | Equipos de 32 bits (muchos Fire TV Stick y TV Box antiguos) |
   | `x86_64` | Emuladores y algunos Chromebook |
   | `universal` | Si no sabes cuál elegir (pesa más) |

4. Instálalo permitiendo **“Instalar apps desconocidas”**. En Android TV puedes usar una app como *Downloader* o *Send Files to TV*.

Requiere **Android 7.0 (API 24)** o superior.

### Navegador (PC, Mac, celular)
1. Descarga el repositorio (**Code → Download ZIP**) y descomprímelo.
2. Instala [Node.js](https://nodejs.org) (versión 18 o superior).
3. En Windows, haz doble clic en `web/iniciar.bat`. En otros sistemas: `cd web && npm install && node server/server.js --open`.
4. Para usarlo desde el celular o el TV de tu casa, usa `web/iniciar-red-local.bat` (o `--lan`).
5. Desde el navegador puedes **instalarla como app (PWA)**.

### Servidor propio (Docker, Linux, Raspberry Pi, NAS)
```bash
git clone https://github.com/CarmelitaSoftworks/Vibe-IPTV.git
cd Vibe-IPTV/web
docker compose up -d        # http://IP-DEL-SERVIDOR:8080
```
Instalación como servicio, contraseña de acceso (`VIBE_PASSWORD`), HTTPS y cómo conectar Tvheadend,
Threadfin o xTeVe: **[docs/SERVIDOR-PROPIO.md](docs/SERVIDOR-PROPIO.md)**.

---

## 🛠️ Compilar desde el código

### En la nube (sin instalar nada)
Cada cambio en la rama `main` compila el APK automáticamente con **GitHub Actions**
([`compilar-apk.yml`](.github/workflows/compilar-apk.yml)). También puedes lanzarlo a mano desde
**Actions → Compilar APK → Run workflow**.

### En tu computador
```bash
cd android
./gradlew assembleDebug
# APKs en android/app/build/outputs/apk/debug/
```
Necesitas JDK 17 y el SDK de Android (o simplemente abre la carpeta `android/` en Android Studio).

### Pruebas de la app web
```bash
cd web
npm test
```

---

## 🧱 Tecnologías

| Parte | Tecnología |
|---|---|
| Android | Kotlin 2.1, Jetpack Compose, Gradle 8.11 / AGP 8.7 |
| Reproducción Android | Media3 ExoPlayer, LibVLC, libmpv |
| Red e imágenes | OkHttp, Coil |
| Web | JavaScript (módulos ES) sin frameworks, PWA |
| Reproducción web | hls.js, mpegts.js, ffmpeg (opcional, vía `ffmpeg-static`) |
| Servidor local | Node.js sin dependencias obligatorias (proxy, conversión y subtítulos) |

### Estructura del proyecto
```
android/   App nativa para Android y Android TV (Kotlin + Compose)
web/       App web / PWA, servidor local en Node.js y archivos Docker
web/deploy Ejemplos de servicio systemd, Caddy y nginx
docs/      Guía de uso e instalación en servidor propio
.github/   Compilación automática del APK
```

---

## 🤝 Contribuir

¡Las contribuciones son bienvenidas! Abre un **issue** para reportar errores o proponer ideas, o envía
un **pull request** con tus mejoras. Te pedimos mantener el enfoque del proyecto: **un reproductor
neutral, sin contenido incluido** (no se aceptan listas, enlaces a canales ni credenciales).

Al reportar un problema con un canal, **no publiques tu usuario, contraseña ni la URL completa de tu lista**.

---

## 🙏 Créditos

**Vibe IPTV** es desarrollado y mantenido por **[Carmelita Softworks](https://github.com/CarmelitaSoftworks)**. 🇨🇱

También existe gracias a otros proyectos de código abierto:

- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) — Apache 2.0
- [LibVLC](https://code.videolan.org/videolan/vlc) de VideoLAN — LGPL 2.1+
- [mpv](https://mpv.io) / [libmpv para Android](https://github.com/jarnedemeulemeester/libmpv-android)
- [hls.js](https://github.com/video-dev/hls.js) — Apache 2.0 · [mpegts.js](https://github.com/xqq/mpegts.js) — Apache 2.0
- [FFmpeg](https://ffmpeg.org) · [OkHttp](https://github.com/square/okhttp) · [Coil](https://github.com/coil-kt/coil)
- [iptv-org/epg](https://github.com/iptv-org/epg) para generar guías XMLTV
- Subtítulos de [OpenSubtitles](https://www.opensubtitles.org), [SubDL](https://subdl.com) y [Podnapisi](https://www.podnapisi.net)
- Inspirado en el diseño de ajustes de [OwnTV](https://github.com/ahXN00/OwnTV)

Los nombres y marcas mencionados pertenecen a sus respectivos dueños; este proyecto no está afiliado a ellos.

---

## ⚖️ Aviso legal

Vibe IPTV es **únicamente un reproductor multimedia**. No aloja, distribuye ni proporciona
canales, listas ni contenido de ningún tipo. Cada usuario es responsable de las fuentes que
agrega y de contar con los derechos para verlas.

---

## 📄 Licencia

Copyright © 2026 Carmelita Softworks.

Distribuido bajo la **[Licencia Pública General de GNU, versión 3 (GPLv3)](LICENSE)**.

Puedes usar, estudiar, modificar y compartir este software libremente. Si distribuyes una versión
modificada, debe mantenerse bajo **GPLv3** y con su **código fuente disponible**.

---

<div align="center">

**Libre · Código abierto · Solo reproductor · Hecho con ❤️ por Carmelita Softworks**

</div>
