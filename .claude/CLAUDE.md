# Cómo trabajamos en Vibe IPTV

Guía para cualquier sesión que trabaje en este repositorio. Léela entera antes de hacer cambios.
Si algo de aquí choca con lo que el usuario pide en la conversación, manda lo que pide el usuario.

## El proyecto en una línea

**Vibe IPTV** es un reproductor IPTV de código abierto (GPLv3) de **Carmelita Softworks** (Chile):
app **Android / Android TV** en Kotlin + Jetpack Compose, y **app web / PWA** con servidor Node propio.
Reproductor neutral: no trae listas ni contenido; el usuario carga su lista M3U o su cuenta Xtream Codes.

## El usuario

- Habla **español de Chile**. Responde siempre en español, claro y sin jerga técnica innecesaria.
- No es programador: quiere resultados que pueda instalar y probar. Explica qué cambió, en qué versión y dónde se ve
  (por ejemplo "Ajustes → Reproducción → Búfer"), no cómo se programó.
- Prueba en equipos reales: **TV box Xiaomi (Mi Box / Mi TV Stick)**, una **Smart TV JVC** con Android y su celular.
  Instala con **Downloader**, código **9370754** (apunta a la última versión "prueba").
- Manda fotos de la TV cuando algo se ve mal: compáralas con las capturas automáticas (más abajo).
- Sé honesto sobre lo que no se probó en un equipo real: compilar y pasar pruebas no es lo mismo que verlo andar.

## Reglas que el usuario pidió (obligatorias)

1. **Nunca repitas ni guardes en el repositorio usuarios, contraseñas o URLs de su proveedor IPTV**, aunque aparezcan
   en capturas o mensajes. En pruebas usa datos inventados (`11112222`, `tv.ejemplo.pro`).
2. **Commits a nombre de Carmelita Softworks, sin rastro de la IA:**
   ```
   git config user.name "Carmelita Softworks"
   git config user.email "carmelitasoftworks@users.noreply.github.com"
   ```
   Sin líneas `Co-Authored-By`, `Claude-Session` ni "Generated with". Nunca uses el correo personal del usuario.
3. **No mencionar a Claude ni a Anthropic en la página principal (README), la app ni la documentación.**
   Sólo aparecen en `CREDITOS.md` / `CREDITS.md`, en la sección "Hecho con vibe coding". Que es un proyecto
   *vibe coded* **sí** se dice, también en el README.
4. **No publicar una versión por cada cambio pequeño.** Se agrupan cambios y se publica cuando hay una cantidad
   considerable, o cuando el usuario dice "actualiza la build" / "actualizar builds al terminar".
5. **Nada de logos, íconos, nombres ni sonidos de marcas** (consolas, Windows, VLC, etc.) en la app ni en el repo.
   Los temas "inspirados en" usan diseño e íconos propios dibujados en código, y nombres genéricos
   (Mosaicos, Ondas, Pulso, Horizonte, Escritorio).
6. El keystore de firma **nunca** va al repositorio: vive en los secretos `VIBE_KEYSTORE_B64` y `VIBE_KEY_PASS`.
   Ver `docs/FIRMA.md`. Si cambiara la firma, los usuarios tendrían que desinstalar.
7. Los respaldos que exporta la app contienen contraseñas: cuando se hable de respaldos, recordar guardarlos en privado.

## Compilar y publicar (todo en GitHub Actions)

No hay Android SDK en las sesiones: **la APK la compila GitHub**. No intentes compilar Android en local.

- `.github/workflows/compilar-apk.yml` corre en cada push a `main`:
  NDK (Whisper) → modelo de voz Vosk para la versión IA → `assembleRelease` (sabores `estandar` e `ia`) →
  `testEstandarReleaseUnitTest` → publicación.
- **Sólo publica** si el último commit del push lleva **`[publicar]`** en el mensaje, o con
  `gh workflow run compilar-apk.yml --ref main -f publicar=true`.
  Publicar crea la versión numerada `v0.1.N` y reemplaza la versión **`prueba`** (a la que apuntan Downloader,
  GitHub Pages y el aviso de actualización de la app). `N` = número de la ejecución.
- **Cada push a `main` cancela la compilación que esté corriendo** (`concurrency: cancel-in-progress`).
  Haz commits locales y **un solo push** por grupo de cambios. Antes de hacer push, mira si hay una compilación con
  `[publicar]` en curso (`gh run list -L 3`) y espera a que termine para no cancelarla.
- Una compilación tarda **~6–7 min**. Espera en ciclos cortos (≤ 10 min por comando):
  ```
  for i in $(seq 1 48); do s=$(gh run list -R CarmelitaSoftworks/Vibe-IPTV -L 1 --json status,conclusion -q '.[0].status+" "+.[0].conclusion'); case "$s" in completed*) echo "$s"; break;; esac; sleep 12; done
  ```
- Si falla: `gh run view <id> --log-failed`, o el resumen que el flujo guarda en la rama **`build-logs`** (`errores.txt`).
- Verifica lo publicado: `gh api repos/CarmelitaSoftworks/Vibe-IPTV/releases/tags/prueba -q .name`
- APK publicadas: `vibe-iptv-{armv7,arm64,x86_64,universal}.apk` y las mismas con `-ia-` (modelo de voz incluido).
  TV / TV box → **armv7**; celular → **arm64**.
- Firma fija: las versiones se instalan encima sin perder listas ni ajustes.

## Ver las pantallas sin un equipo (capturas automáticas)

`gh workflow run capturas.yml --ref main` dibuja las pantallas con Robolectric + Roborazzi
(`android/app/src/test/.../ui/ThemeCapturesTest.kt`, 1920×1080 como una TV, y tamaños de teléfono) y las sube a la rama
**`capturas`**. Descárgalas (`git fetch origin capturas` + `git show origin/capturas:archivo.png`) y **míralas** antes de
decir que algo "se ve bien". Esas pruebas sólo corren con `-Pcapturas=true`; en las pruebas normales se saltan.
Para cargar datos de prueba sin red: `AppViewModel.loadPreview(...)`.

## Estructura

```
android/app/src/main/java/cl/iptv/player/
  MainActivity.kt          entrada, teclas del control, imagen en imagen, enlaces vibeiptv://
  ui/AppViewModel.kt       estado central (listas, favoritos, Seguir viendo, perfiles, búfer, temas…)
  ui/App.kt                qué pantalla se muestra (menú según el tema, reproductor, mini reproductor)
  ui/PlayerScreen.kt       reproductor: ExoPlayer + VLC + MPV, vigilantes de arranque y congelamiento
  ui/MenuScreen.kt         menú clásico; temas con menú propio: MetroScreen, WaveScreen, ConsoleThemes, DeskScreen
  ui/SettingsScreen.kt     ajustes (filas Toggle/Choice/TextField/Action/Danger/Info/Slider)
  ui/I18n.kt               `Text` que traduce solo; `tr()` para textos fuera de Text
  data/                    Xtream, M3U, EPG, almacenamiento, subtítulos, búfer, equipo, Whisper/Vosk…
android/app/src/test/      pruebas JUnit/Robolectric (deben pasar)
web/                       app web (módulos ES sin framework) + server/ (Node: proxy, ffmpeg, subtítulos)
web/test/                  `cd web && npm test` (debe pasar)
i18n/en.json               diccionario español → inglés compartido por web y Android
docs/                      GUIA.md / GUIDE.md, servidor propio, Downloader, firma; docs/img capturas
```

## Convenciones de código

- **Textos de la interfaz en español**. Cada texto nuevo se traduce en `i18n/en.json` y luego `node i18n/build.mjs`
  (genera `web/js/i18n-en.js` y `android/app/src/main/assets/i18n/en.json`). Admite `{}` para partes variables.
  En Android, `Text(...)` del paquete `ui` traduce solo; fuera de un Text usa `tr("...")`.
- Comentarios del código en español, explicando el porqué.
- Lo que valga para ambas apps se hace en **Android y web** (la web se prueba con `npm test`).
- Equipos modestos: respetar el **modo liviano** (`Perf.lite`): sin sombras, animaciones ni efectos caros.
  Control remoto: todo debe poder usarse con ▲▼◄► y OK, sin pantalla táctil.
- Ajustes nuevos: campo en `data/Models.kt` (Settings) + lectura/escritura en `data/Storage.kt` + fila en
  `SettingsScreen.kt` (+ `web/js/storage.js` y `web/js/settings.js` si aplica a la web).
- Al terminar un grupo de cambios: README (es/en) y `docs/GUIA.md` / `docs/GUIDE.md` al día.

## Trabajo en paralelo (coordinación por GitHub)

A veces hay **dos sesiones** trabajando en el repo. Se coordinan con los *issues* de GitHub:
el issue **#3 "Tablero: quién trabaja en qué"** explica el procedimiento.

- **Al empezar:** `git pull` y revisa los issues con la etiqueta **en curso**
  (`gh api "repos/CarmelitaSoftworks/Vibe-IPTV/issues?labels=en%20curso" -q '.[] | "#\(.number) \(.title)"'`).
  Cada uno dice la rama y los archivos que esa sesión está tocando: no toques esos archivos.
- **Tu tarea:** crea (o toma) un issue con la etiqueta **en curso**, la rama y los archivos, y comenta en #3 qué tomaste.
  Tareas acordadas sin dueño llevan la etiqueta **pendiente**.
- Trabaja en **tu rama** (los push a ramas que no son `main` no compilan ni cancelan nada).
- **Al terminar:** `git pull --rebase` de `main`, resuelve choques, un solo push a `main` (sin compilación con
  `[publicar]` en curso), cierra tu issue y comenta en #3 qué versión salió.
- Cambios que sólo tocan documentación o esta guía: commit con `[skip ci]` para no gatillar ni cancelar compilaciones.
- Nota: desde estas sesiones GitHub sólo acepta la API REST (`gh api ...`), no `gh issue` / `gh pr` (usan GraphQL).
- Avisa al usuario qué archivos estás tocando si otra sesión está activa.

## Cómo responder al usuario al terminar

Corto y concreto: número de versión publicada, qué APK bajar, qué cambió y dónde verlo, qué no se pudo probar
y el siguiente paso si lo hay. Sin repetir credenciales y sin pegar enlaces de artefactos innecesarios.
