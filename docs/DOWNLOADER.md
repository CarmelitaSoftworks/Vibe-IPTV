# 📺 Instalar Vibe IPTV en el TV con Downloader

> Volver al [README](../README.md) · 🇬🇧 [English](DOWNLOADER.en.md)

**Downloader** (de AFTVnews) es una app gratuita para Android TV, Google TV y Fire TV que descarga
e instala APK escribiendo una dirección con el control remoto. Es la forma más fácil de instalar
Vibe IPTV en un TV sin computador ni cables.

## Código de Downloader

<div align="center">

### **9370754**

</div>

Escribe **9370754** en Downloader y pulsa **Go**: descarga la versión para TV. Es lo mismo que escribir
`carmelitasoftworks.github.io/Vibe-IPTV/tv`, pero más corto.

## Enlaces cortos

| Equipo | Escribe en Downloader |
|---|---|
| **TV, Fire TV, TV Box** (recomendado para TV) | `9370754` o `carmelitasoftworks.github.io/Vibe-IPTV/tv` |
| Celular o tablet | `carmelitasoftworks.github.io/Vibe-IPTV/movil` |
| Si no sabes cuál (pesa más) | `carmelitasoftworks.github.io/Vibe-IPTV/universal` |

Los enlaces siempre apuntan a la **versión más reciente**: no cambian cuando se publica una nueva.
Página de descarga: **https://carmelitasoftworks.github.io/Vibe-IPTV/**

> [!TIP]
> Escribe `Vibe-IPTV` con mayúsculas tal como aparece. Si el enlace corto no funciona, usa el
> directo: `github.com/CarmelitaSoftworks/Vibe-IPTV/releases/download/prueba/vibe-iptv-armv7.apk`

---

## Android TV y Google TV (Chromecast con Google TV, TCL, Sony, Philips, Xiaomi, TV Box…)

1. **Instala Downloader.** Abre **Google Play** en el TV, busca *Downloader* (ícono naranjo, de AFTVnews) e instálala.
2. **Permite que instale apps.**
   - Google TV: **Ajustes → Sistema → Acerca de → Compilación del SO de Android TV**: pulsa OK **7 veces** hasta que diga que eres desarrollador. Luego **Ajustes → Apps → Seguridad y restricciones → Fuentes desconocidas → Downloader → activar**.
   - Android TV (otros modelos): **Ajustes → Apps → Acceso especial de apps → Instalar apps desconocidas → Downloader → Permitir**. En algunos equipos está en **Ajustes → Seguridad y restricciones → Fuentes desconocidas**.
3. **Abre Downloader.** La primera vez pide permiso para acceder a archivos: elige **Permitir**.
4. En la pestaña **Home**, selecciona el recuadro de dirección y escribe el código:
   ```
   9370754
   ```
   (o la dirección `carmelitasoftworks.github.io/Vibe-IPTV/tv`).
5. Pulsa **Go**. Aparece un momento una página de AFTVnews con el destino y luego empieza la descarga tarda un poco (el archivo pesa unos 85 MB).
6. Cuando termine, pulsa **Instalar** y luego **Abrir**.
7. Downloader pregunta si quieres **borrar el APK**: pulsa **Delete** dos veces para liberar espacio. Vibe IPTV queda instalada y aparece en la pantalla de inicio del TV (o en **Apps**).

## Fire TV (Fire TV Stick, Fire TV Cube, televisores con Fire TV)

1. En el inicio, ve a la **lupa (Buscar)**, escribe *Downloader* e instálala.
2. Activa las opciones para desarrolladores: **Configuración → Mi Fire TV → Acerca de** → selecciona el nombre del equipo (*Fire TV Stick*) y pulsa OK **7 veces**.
3. Vuelve a **Mi Fire TV → Opciones para desarrolladores → Instalar apps desconocidas → Downloader → Activado**.
4. Abre Downloader, escribe `9370754` (o dicta el número con el control de voz Alexa) y pulsa **Go**.
5. **Instalar → Abrir**, y borra el APK cuando lo pregunte.

> En Fire TV, la app queda en **Apps** (ícono de los tres cuadrados). Mantén pulsado OK sobre ella → **Mover** para dejarla al principio.

## Actualizar a una versión nueva

Repite los pasos 4 a 6 con el mismo enlace: se instala encima y **conservas tus listas, favoritos y ajustes**.

---

## ¿Qué versión elijo?

| Archivo | Para qué equipos |
|---|---|
| `vibe-iptv-armv7.apk` (enlace **/tv**) | Casi todos los TV, Fire TV y TV Box. Aunque el equipo sea de 64 bits, la mayoría de los TV usan apps de 32 bits. |
| `vibe-iptv-arm64.apk` (enlace **/movil**) | Celulares y tablets actuales, y TV Box de 64 bits. |
| `vibe-iptv-x86_64.apk` | Emuladores y algunos Chromebook. |
| `vibe-iptv-universal.apk` (enlace **/universal**) | Sirve en todos, pero pesa mucho más. Úsalo si los otros no se instalan. |

Requiere **Android 7.0** o superior. El motor MPV necesita Android 8.0; en equipos más antiguos se usan ExoPlayer y VLC.

## Problemas comunes

| Mensaje o problema | Solución |
|---|---|
| **«App no instalada»** o **«El paquete no es compatible»** | Prueba con el enlace `/universal`. Si ya tenías otra versión de Vibe IPTV instalada desde otro lugar, desinstálala primero. |
| **«Por seguridad, tu TV no puede instalar apps desconocidas»** | Falta el paso 2: permite a Downloader instalar apps desconocidas. |
| **Downloader abre una página en vez de descargar** | Espera unos segundos: la página redirige sola. Si no, usa el enlace directo del recuadro de arriba. |
| **«Espacio insuficiente»** | Borra APK antiguos desde la pestaña **Files** de Downloader, o desinstala apps que no uses. |
| **El código no funciona** | Escribe sólo el número `9370754`, sin `aftv.news/`. Si sigue fallando, usa la dirección `carmelitasoftworks.github.io/Vibe-IPTV/tv`. |
| **El enlace corto no carga (error 404)** | Revisa mayúsculas y guiones: `Vibe-IPTV`. También puedes usar el enlace directo. |
| **No aparece en el inicio del TV** | Búscala en **Apps** o en **Ajustes → Apps → Ver todas las apps**. |

> [!NOTE]
> Vibe IPTV es **solo un reproductor**: no trae canales. Después de instalarla, agrega tu lista
> M3U o tu cuenta Xtream Codes. Si tu proveedor te mandó los datos en una imagen, usa la opción
> **Desde una captura** y se rellenan solos.
