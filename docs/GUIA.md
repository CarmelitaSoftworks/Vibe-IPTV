# Guía de uso — Vibe IPTV

> Volver al [README](../README.md) · 🇬🇧 [English](GUIDE.md)

Dos apps nativas que comparten el mismo diseño de menú (cuadrícula de mosaicos redondeados con paginación lateral y barra inferior con categoría y fecha/hora):

| | Web (navegador) | Android / Android TV |
|---|---|---|
| Tecnología | HTML/CSS/JS sin framework + hls.js + mpegts.js (+ ffmpeg) | Kotlin + Jetpack Compose + ExoPlayer, VLC y MPV |
| Listas M3U / M3U8 | URL o archivo | URL o archivo |
| Xtream Codes | En vivo, películas y series (episodios) | En vivo, películas y series (episodios) |
| Guía EPG XMLTV (.xml / .xml.gz) | Sí (ahora / luego + progreso) | Sí (ahora / luego + progreso) |
| Favoritos, búsqueda, categorías, varias listas | Sí | Sí |
| Control remoto / D-pad | Flechas, Enter, Atrás, RePág/AvPág | Lanzador de Android TV, banner, D-pad, CH+/CH−, MENÚ, INFO |
| Subtítulos | Pistas internas, archivo local, OpenSubtitles · SubDL · Podnapisi | Pistas internas, archivo local, OpenSubtitles · SubDL · Podnapisi, "Abrir en VLC" |
| Idioma | Español o inglés (Ajustes → Apariencia → Idioma) | Español o inglés (Ajustes → Apariencia → Idioma) |

> La app es sólo un **reproductor**: no incluye canales. Úsala con listas a las que tengas acceso legítimo.

---

## 1. App web

**En Windows lo más fácil:** doble clic en `web/iniciar.bat` (abre el navegador solo) o en `web/iniciar-red-local.bat` para usarla también desde el TV o el celular de tu red (muestra la dirección a usar). Deja la ventana negra abierta mientras usas la app.

```bash
cd web
node server/server.js          # http://localhost:8080
# o accesible desde el TV / celular de tu red local:
node server/server.js --lan
```

Requiere Node 18+ y **no tiene dependencias obligatorias** (hls.js y mpegts.js ya están en `web/vendor/`; ffmpeg es opcional).

Para dejarlo instalado en un NAS, Raspberry Pi o servidor, mira [Instalar en tu propio servidor](SERVIDOR-PROPIO.md).

**¿Por qué un servidor?** Casi ningún proveedor IPTV permite que un navegador lea sus streams (CORS) y muchos usan `http://`. `server.js` sirve la app y hace de proxy: reescribe las listas HLS para que los segmentos también pasen por él, sigue redirecciones y envía un User-Agent de reproductor. La app primero intenta la conexión directa y, si falla, usa el proxy automáticamente (se puede cambiar en Ajustes). No expongas el servidor a Internet sin contraseña (`VIBE_PASSWORD`): es un proxy pensado para tu PC o tu red local.

También se puede instalar como app (PWA) desde Chrome en Android: menú ⋮ → "Instalar app".

**Teclas:** flechas para moverse (← → al borde cambian de página), Enter para abrir, Esc/Atrás para volver, `F` marca favorito, `/` busca. En el reproductor: ▲/▼ cambia de canal, ◄/► avanza 10 s en películas, Enter muestra los controles.

Pruebas: `npm test` (parsers M3U/XMLTV/SRT, proxy, contraseña y traducciones).

## 2. App Android / Android TV

1. Abre la carpeta `android/` con **Android Studio** (Meerkat o más nuevo). Descargará Gradle 8.13 y las dependencias.
2. Conecta un teléfono o un Android TV con depuración USB/ADB y pulsa ▶ *Run*.
3. Para generar un APK: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*, o en terminal `./gradlew assembleRelease`. Se generan APK por arquitectura (más livianos): la mayoría de los Android TV y TV box usan `armeabi-v7a` o `arm64-v8a`; si no sabes cuál, usa el `universal` (el release se firma con la clave de depuración para poder probarlo; cambia la firma antes de publicar).

**Sin Android Studio (compilar en la nube):** cada cambio en GitHub ejecuta "Compilar APK" en la pestaña **Actions** (o pulsa *Run workflow*). Al terminar (≈10 min), el APK queda en **Releases → versión de prueba** y en *Artifacts* (**Vibe-IPTV-APK**). Usa el APK `universal` si no sabes la arquitectura de tu equipo. Si la compilación falla, el registro completo queda en la rama `build-logs`.

Instalar en el teléfono: copia el APK, ábrelo y permite "Instalar apps desconocidas" para el administrador de archivos o el navegador.

**Lo más fácil en el TV:** la app *Downloader* con el enlace `carmelitasoftworks.github.io/Vibe-IPTV/tv` (paso a paso en [DOWNLOADER.md](DOWNLOADER.md)).

Instalar en un Android TV sin Android Studio: activa *Opciones de desarrollador → Depuración por red* en el TV y ejecuta `adb connect IP_DEL_TV` y `adb install app-release.apk`. También sirve copiar el APK con una app como "Send Files to TV" o "Downloader".

**Android TV:** aparece en el lanzador de TV (banner incluido), no requiere pantalla táctil y todo se maneja con el control: D-pad en la cuadrícula (al llegar al borde cambia de página), mantener pulsado OK o la tecla MENÚ marca favorito, CH+/CH− y ▲/▼ cambian de canal en el reproductor, MENÚ abre las opciones (favorito, subtítulos, Abrir en VLC), INFO muestra la guía.

**Teléfono:** la app se abre en horizontal, como un TV. Desliza la cuadrícula para cambiar de página y mantén pulsado un mosaico para marcarlo como favorito; el reproductor admite imagen en imagen (PiP) al salir con Inicio.

## 3. Secciones: TV en vivo, Películas y Series

Al abrir una lista aparece el **Inicio** con tres mosaicos grandes (TV en vivo, Películas, Series) y la cantidad de contenido de cada uno; las mismas secciones están siempre en las pestañas de arriba. Atrás vuelve al Inicio.

- **Xtream Codes** ya separa las tres secciones.
- **Listas M3U** se clasifican solas: `/movie/` o `/series/` en la URL, extensiones de video (`.mp4`, `.mkv`…), nombres de grupo (`Películas`, `VOD`, `Cine`, `Series`, `Novelas`, `Temporada`…) y patrones de episodio (`S01E02`, `T1E2`, `1x02`). Los episodios sueltos se agrupan en su serie y se ordenan por temporada y capítulo.

## 4. Guía de programación (EPG)

- Se usa la guía que indique la lista (`x-tvg-url`), la de Xtream (`xmltv.php`) o la que escribas al editar la lista. Puedes poner **varias separadas por coma**: se combinan.
- **Se actualiza sola**: al añadir una lista, cada vez que abres la app, al volver a la app tras 30 minutos y cada 4 horas con la app abierta. Mientras descarga se muestra la última guía guardada, y si falla la conexión se mantiene esa. En **Ajustes** ves cuándo se actualizó y tienes "Actualizar guía ahora".
- Para generar tu propia guía con software libre: [iptv-org/epg](https://github.com/iptv-org/epg) (para Chile sirven `mi.tv`, `gatotv.com` y `directv.com`):

  ```bash
  git clone --depth 1 -b master https://github.com/iptv-org/epg.git
  cd epg && npm install
  npm run grab --- --sites=mi.tv --days=2      # genera guide.xml
  ```

  Copia `guide.xml` a `web/` y usa `guide.xml` como guía (web) o `http://IP-DE-TU-PC:8080/guide.xml` (Android/TV con el servidor corriendo en la red local).

## 5. Pantalla de listas y Ajustes

- **Pantalla de listas**: cuatro opciones grandes para añadir (lista M3U por enlace, archivo M3U, Xtream Codes y desde una captura) y tus listas guardadas para abrirlas con un clic. Se abre con el botón de la lista (arriba a la izquierda).
- **Ajustes** (botón con engranaje), al estilo de OwnTV: secciones a la izquierda con su número de opciones y, a la derecha, cada ajuste con su valor; buscador arriba. Con el control: ▲▼ entre secciones, ► entra, ◄ vuelve.
  - Rápido: números de canal, guía bajo los mosaicos, siguiente episodio automático, animaciones.
  - Listas y guía · Apariencia (**idioma** español/inglés; el menú es **blanco por defecto**; tema oscuro y color de acento opcionales; tamaño de mosaicos) · Reproducción · Subtítulos · Red (sólo web) · Inicio de la app (última lista, pantalla de listas o último canal) · Datos · Acerca de.

## 6. Reproductor y canales de TV de cable (AC-3, MP2, MPEG-2)

**Controles (web):** anterior/siguiente, ±10 s, volumen, lista lateral de canales con la guía (◄ en un canal en vivo), subtítulos, audio, calidad, formato de pantalla (ajustar, rellenar, estirar, zoom), velocidad, información técnica (motor, resolución, códecs, búfer), favorito, imagen en imagen, "Abrir en VLC" y pantalla completa. Teclas: M silencio, I información, L lista, +/− volumen.

**Formatos de la TV de cable:** muchos canales en vivo traen audio **AC-3 / MP2** o video **MPEG-2 entrelazado**, que los navegadores no reproducen.
- **Web:** el servidor local los convierte al vuelo con **ffmpeg** (sólo lo necesario: copia el video H.264 y convierte el audio a AAC; si el video es MPEG-2 lo desentrelaza y lo pasa a H.264 a 720p). `iniciar.bat` instala ffmpeg sola la primera vez (`npm install`, unos 70 MB). En Ajustes → Reproducción: *Automático* (cuando el canal falla o queda sin sonido), *Siempre en TV en vivo* o *Nunca*, y la calidad de conversión.
- **Android / TV:** tres motores: **ExoPlayer**, **VLC (LibVLC)** y **MPV (libmpv, el mismo de OwnTV)**. En *Automático* prueba en ese orden: si ExoPlayer no puede, pasa a VLC y, si VLC tampoco, a MPV (Android 8.0+); los siguientes canales en vivo parten con el último motor que funcionó. En el menú del reproductor (tecla MENÚ) se puede cambiar de motor, el formato de pantalla y la pista de audio.

## 7. Subtítulos de varias fuentes

La búsqueda consulta **a la vez** varias fuentes y muestra de cuál viene cada resultado:

| Fuente | Clave | Notas |
|---|---|---|
| OpenSubtitles | No necesita | Se usa el complemento abierto de Stremio y Cinemeta para encontrar el título. Con tu API key (opcional) también se consulta la API oficial, la misma base de VLSub de VLC. |
| SubDL | Gratis en subdl.com | Mucho catálogo en español latino. |
| Podnapisi | No necesita | |

- Se descomprimen solos los `.zip`, se convierten `.ass`/`.ssa`, y se corrige la codificación: los `.srt` en español que vienen en Windows-1252 ya no muestran `Ã±`.
- **Desfase:** si el subtítulo va adelantado o atrasado, en el menú de subtítulos usa "Aparecen antes / después" (pasos de 0,5 s).
- También puedes cargar tu propio archivo y elegir las pistas que trae el video.
- En la web la búsqueda la hace el servidor local (por eso necesita `iniciar.bat` o el servidor); en la vista previa dentro de Claude sólo queda OpenSubtitles con clave.

## 8. Categorías y subcategorías

Dentro de TV en vivo, Películas y Series aparece una barra con las categorías de la lista y, si los grupos traen subniveles (`CHILE | DEPORTES`, `Series » Drama » Turcas`, `LATINO - Noticias`), una segunda barra con las subcategorías. Un prefijo que sólo repite la sección ("Películas | Acción") se omite.

## 9. Cargar una lista desde una captura de pantalla

¿Tu proveedor te mandó los datos por WhatsApp, correo o en una imagen? Usa **Desde una captura** en la pantalla de listas (o **📷 Rellenar desde una captura…** dentro del formulario) y elige la imagen. La app lee el texto y rellena lo que encuentre:

- **Xtream Codes**: servidor (también si el puerto viene aparte), usuario y contraseña, con etiquetas en español, inglés o portugués (`Usuario`, `User`, `Login`, `Contraseña`, `Password`, `Clave`, `Servidor`, `Host`, `DNS`, `URL`, `Puerto`…), en la misma línea o en la siguiente.
- **Enlaces completos**: un enlace `get.php?username=…&password=…` trae todo de una vez; también reconoce enlaces `.m3u`/`.m3u8` y la guía `xmltv.php`.
- Los campos rellenados quedan marcados en color y la contraseña se muestra para que la revises: la lectura puede confundir letras parecidas (`l`, `I` y `1`, `O` y `0`). **Nada se guarda hasta que pulses Guardar y abrir.**
- En la web también puedes **pegar** la captura con Ctrl+V en la pantalla de listas.
- **Privacidad**: el texto se lee en tu equipo. En Android con ML Kit (el modelo viene dentro de la app y funciona sin Internet). En la web con Tesseract.js: si abres la app con `iniciar.bat` o tu servidor, el lector se instala ahí; si no, se descarga de jsDelivr la primera vez. La imagen nunca se envía a ningún servidor.
- Consejo: recorta la imagen para que sólo se vean los datos, y evita fotos movidas o con reflejos.

## 10. Idioma

La app está en **español** y en **inglés**. Por defecto sigue el idioma del sistema (español si el equipo está en español; si no, inglés) y se puede fijar en **Ajustes → Apariencia → Idioma**. Las traducciones están en `i18n/en.json`, un solo diccionario para la web y Android: para corregir o agregar un texto, edítalo ahí y ejecuta `node i18n/build.mjs`.

## Versión de vista previa (artefacto)

Hay una vista previa publicada dentro de Claude con contenido de demostración (6 canales, 3 películas y 1 serie). Ese entorno bloquea las conexiones a servidores externos, así que sirve para probar el menú, la navegación y el reproductor, pero no listas reales.

## Estructura

```
web/
  index.html, css/styles.css, sw.js, manifest.webmanifest
  js/app.js        interfaz, cuadrícula, paneles, reproductor, teclado/mando
  js/m3u.js        parser M3U
  js/xtream.js     cliente Xtream Codes
  js/epg.js        parser XMLTV
  js/player.js     hls.js / mpegts.js / nativo, reintento por proxy
  js/subtitles.js  subtítulos de varias fuentes, SRT→VTT
  js/nav.js        navegación espacial con flechas
  js/i18n.js       traducción al inglés
  js/ocr.js, js/ocr-parse.js   lectura de capturas y reconocimiento de los datos
  server/          servidor + proxy + subtítulos + conversión ffmpeg
  Dockerfile, docker-compose.yml, deploy/   instalación en servidor propio
i18n/en.json       diccionario español → inglés (web y Android)
android/
  app/src/main/java/cl/iptv/player/
    MainActivity.kt          teclas del mando, PiP, detección de TV
    data/                    modelos, M3U, Xtream, EPG, subtítulos, almacenamiento, capturas (ScreenshotParser/Reader)
    ui/SplashScreen.kt       pantalla de inicio
    ui/MenuScreen.kt         cuadrícula de mosaicos + barra inferior
    ui/PlayerScreen.kt       reproductor, guía, opciones, subtítulos
    ui/VlcEngine.kt, ui/MpvEngine.kt   motores VLC y MPV
    ui/SettingsScreen.kt     ajustes
    ui/I18n.kt               traducción al inglés
```
