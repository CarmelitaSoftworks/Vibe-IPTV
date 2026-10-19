# 🖥️ Instalar Vibe IPTV en tu propio servidor

> Volver al [README](../README.md) · [Guía de uso](GUIA.md) · 🇬🇧 [English](SELF-HOSTING.md)

Esta guía es para quien tiene **un equipo siempre encendido**: un NAS, una Raspberry Pi, un mini PC,
un PC con Windows o Linux, o un VPS. También sirve si tienes **tu propio servidor IPTV**
(Tvheadend, Threadfin, xTeVe, Dispatcharr u otro) y quieres usar Vibe IPTV como reproductor.

Al instalarlo así, todos los equipos de tu casa (TV, celulares, notebooks) abren Vibe IPTV desde el
navegador con una dirección como `http://192.168.1.50:8080`, sin instalar nada en cada uno.

---

## ¿Qué hace el servidor?

El servidor de Vibe IPTV es un programa pequeño hecho en Node.js (no requiere librerías extra) que:

- entrega la app web a los equipos de tu red;
- actúa como **intermediario (proxy)** con tu proveedor o servidor IPTV, para evitar los bloqueos del navegador (CORS, mezcla http/https);
- **convierte al vuelo** con ffmpeg los canales que el navegador no puede reproducir (audio AC-3/MP2, video MPEG-2);
- busca y descarga **subtítulos** de OpenSubtitles, SubDL y Podnapisi.

> [!WARNING]
> Como reenvía conexiones, **no lo publiques en Internet sin contraseña**. Lee la sección
> [Acceso desde fuera de casa](#-acceso-desde-fuera-de-casa).

**Requisitos:** cualquier equipo con 512 MB de RAM libre basta. La conversión con ffmpeg sí usa CPU:
en una Raspberry Pi 4 se puede convertir un canal a la vez; en un mini PC o NAS x86, sin problemas.

---

## Opción A · Docker (recomendado para NAS y servidores)

Funciona en cualquier Linux con Docker, y en NAS con soporte de contenedores (Synology Container
Manager, QNAP Container Station, Unraid, TrueNAS, CasaOS, Portainer…). La imagen ya incluye ffmpeg.

```bash
git clone https://github.com/CarmelitaSoftworks/Vibe-IPTV.git
cd Vibe-IPTV/web
docker compose up -d
```

Abre `http://IP-DEL-SERVIDOR:8080` desde cualquier equipo de tu red.

Para cambiar el puerto o poner contraseña, edita `web/docker-compose.yml`:

```yaml
    ports:
      - "9000:8080"            # ahora sería http://IP:9000
    environment:
      - TZ=America/Santiago
      - VIBE_PASSWORD=una-clave-larga
```

y aplica los cambios con `docker compose up -d`.

**Actualizar a una versión nueva:**
```bash
cd Vibe-IPTV && git pull
cd web && docker compose up -d --build
```

> En NAS con interfaz gráfica: crea un “proyecto” o “stack” nuevo, apunta a la carpeta `web/` del
> repositorio descargado y pega el contenido de `docker-compose.yml`.

---

## Opción B · Linux o Raspberry Pi como servicio (sin Docker)

### 1. Instala Node.js (18 o superior), ffmpeg y git
```bash
sudo apt update
sudo apt install -y nodejs ffmpeg git
node --version     # debe decir v18 o más
```
Si tu distribución trae una versión más antigua de Node.js, instálala desde [nodejs.org](https://nodejs.org) o con [NodeSource](https://github.com/nodesource/distributions).

### 2. Descarga Vibe IPTV
```bash
sudo useradd --system --home /opt/vibe-iptv --shell /usr/sbin/nologin vibe
sudo git clone https://github.com/CarmelitaSoftworks/Vibe-IPTV.git /opt/vibe-iptv
sudo chown -R vibe:vibe /opt/vibe-iptv
```

### 3. Pruébalo a mano
```bash
cd /opt/vibe-iptv/web
HOST=0.0.0.0 node server/server.js
```
Debe mostrar la dirección de tu red (por ejemplo `http://192.168.1.50:8080`) y
`Conversión de formatos (ffmpeg): activa.` Ábrela desde otro equipo y luego detén el servidor con `Ctrl + C`.

### 4. Déjalo instalado como servicio (arranca solo al encender)
```bash
sudo cp /opt/vibe-iptv/web/deploy/vibe-iptv.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now vibe-iptv
systemctl status vibe-iptv          # debe decir "active (running)"
journalctl -u vibe-iptv -f          # ver el registro en vivo
```
Para poner contraseña o cambiar el puerto, edita las líneas `Environment=` del archivo de servicio
(`sudo systemctl edit --full vibe-iptv`) y reinícialo con `sudo systemctl restart vibe-iptv`.

**Actualizar:**
```bash
cd /opt/vibe-iptv && sudo -u vibe git pull && sudo systemctl restart vibe-iptv
```

---

## Opción C · PC con Windows siempre encendido

1. Instala [Node.js](https://nodejs.org) (versión LTS) y descarga el repositorio (**Code → Download ZIP**). Descomprímelo, por ejemplo, en `C:\VibeIPTV`.
2. Haz doble clic en `web\iniciar-red-local.bat`. La primera vez instala ffmpeg automáticamente y muestra la dirección para los otros equipos.
3. Si Windows pregunta por el **Firewall**, permite el acceso en **redes privadas**.

**Para que arranque solo al encender el PC** (sin dejar una ventana abierta):

1. Abre el **Programador de tareas** → **Crear tarea…**
2. Pestaña *General*: nombre `Vibe IPTV`, marca **“Ejecutar tanto si el usuario inició sesión como si no”**.
3. Pestaña *Desencadenadores*: **Nuevo… → Al iniciar el sistema**.
4. Pestaña *Acciones*: **Nuevo… → Iniciar un programa**
   - Programa: `C:\Program Files\nodejs\node.exe`
   - Argumentos: `server\server.js --lan`
   - Iniciar en: `C:\VibeIPTV\web`
5. Pestaña *Configuración*: desmarca **“Detener la tarea si se ejecuta durante más de…”**.

Para usar contraseña, crea una variable de entorno del sistema `VIBE_PASSWORD` (Panel de control →
Sistema → Configuración avanzada → Variables de entorno) y reinicia la tarea.

---

## Abrirlo en el TV, el celular y otros equipos

- Usa la dirección que muestra el servidor al arrancar: `http://IP-DEL-SERVIDOR:8080`.
- Conviene que el servidor tenga siempre **la misma IP**: resérvala en tu router (opción *DHCP estático* o *reserva de IP*).
- En el celular o el TV, abre el menú del navegador → **Agregar a la pantalla de inicio** para tener un acceso directo.
- La instalación como app completa (PWA) la ofrecen los navegadores solo con **HTTPS**; dentro de casa, el acceso directo funciona igual.
- En **Android y Android TV** también puedes usar la **app nativa** (APK), que no necesita este servidor: se conecta directo a tu lista y reproduce con ExoPlayer, VLC o MPV.

---

## 📡 Usar tu propio servidor IPTV como fuente

Si tú mismo sirves los canales (sintonizador DVB, decodificador, IPTV de tu operador reorganizada, etc.),
en Vibe IPTV agrega una lista **M3U por URL** con la dirección de tu servidor y, en Ajustes → Listas y guía,
la dirección de su **guía XMLTV**. Direcciones habituales (pueden variar según tu configuración):

| Servidor | Lista M3U | Guía XMLTV |
|---|---|---|
| **Tvheadend** | `http://IP:9981/playlist/channels.m3u` | `http://IP:9981/xmltv/channels` |
| **Threadfin** | `http://IP:34400/m3u/threadfin.m3u` | `http://IP:34400/xmltv/threadfin.xml` |
| **xTeVe** | `http://IP:34400/m3u/xteve.m3u` | `http://IP:34400/xmltv/xteve.xml` |
| **Dispatcharr** y otros | La URL M3U que muestra su panel | La URL EPG/XMLTV que muestra su panel |
| Panel compatible con **Xtream Codes** | Usa la opción *Xtream Codes* con servidor, usuario y contraseña | Se configura sola |

Consejos:
- Usa la **IP** del servidor (por ejemplo `192.168.1.20`) en lugar de nombres como `servidor.local`, que algunos TV no resuelven.
- Si tu servidor pide usuario y contraseña, puedes incluirlos en la URL: `http://usuario:clave@IP:9981/playlist/channels.m3u`.
  En Tvheadend, otra opción es crear un usuario solo para streaming o habilitar el acceso por *ticket*/*persistent auth*.
- Si los canales salen en **MPEG-2 o AC-3** (típico de TDT y cable), el navegador los verá gracias a la conversión con ffmpeg del servidor de Vibe IPTV.
  En Android, el motor VLC o MPV los reproduce directamente. Si tu servidor tiene perfiles de transcodificación (por ejemplo, `?profile=pass` o un perfil H.264 en Tvheadend), también puedes usarlos.
- Si Vibe IPTV y tu servidor IPTV están **en el mismo equipo**, usa igual la IP de la red (no `localhost`), así la misma lista sirve para el TV y el celular.

---

## 🌍 Acceso desde fuera de casa

Recomendado, de más a menos seguro:

1. **VPN privada** (lo más simple y seguro): instala [Tailscale](https://tailscale.com) o [WireGuard](https://www.wireguard.com) en el servidor y en tu celular. Abres `http://IP-TAILSCALE:8080` como si estuvieras en casa, sin abrir puertos del router.
2. **Dominio con HTTPS + contraseña**: si de verdad necesitas publicarlo,
   - define siempre `VIBE_PASSWORD` (el navegador la pide una sola vez);
   - ponlo detrás de un proxy con HTTPS. Hay ejemplos listos en [`web/deploy/Caddyfile`](../web/deploy/Caddyfile) (certificado automático) y [`web/deploy/nginx.conf`](../web/deploy/nginx.conf);
   - abre en el router solo el puerto 443 hacia el proxy, **nunca el 8080 directo**.

> [!CAUTION]
> Sin contraseña, cualquiera que encuentre tu dirección podría usar tu servidor como intermediario
> y tu conexión. Además, muchos proveedores IPTV limitan las conexiones simultáneas por cuenta.

---

## ⚙️ Opciones del servidor

| Variable | Para qué sirve | Valor por defecto |
|---|---|---|
| `PORT` | Puerto en el que escucha | `8080` |
| `HOST` | `0.0.0.0` para aceptar otros equipos de la red; `127.0.0.1` solo este equipo | `127.0.0.1` (Docker: `0.0.0.0`) |
| `VIBE_PASSWORD` | Contraseña de acceso (cualquier usuario + esta clave) | sin contraseña |
| `FFMPEG_PATH` | Ruta a ffmpeg si no se detecta solo | se busca automáticamente |
| `IPTV_UA` | User-Agent con que se piden los canales | `VLC/3.0.21 LibVLC/3.0.21` |
| `VIBE_LANG` | Idioma de los mensajes de la consola (`es` o `en`) | el del sistema |

También acepta las opciones `--lan` (equivale a `HOST=0.0.0.0`) y `--open` (abre el navegador al iniciar).

---

## 🩺 Problemas comunes

| Síntoma | Qué revisar |
|---|---|
| Desde otro equipo no abre la página | El servidor debe arrancar con `HOST=0.0.0.0` (o `--lan`); revisa el firewall del servidor (puerto 8080, red privada) y que ambos equipos estén en la misma red. |
| `El puerto 8080 ya está en uso` | Ya hay otra copia corriendo u otro programa usa ese puerto: cambia `PORT`. |
| Algunos canales no se ven en el navegador | En Ajustes → Reproducción, *Conversión (ffmpeg)* debe decir *Disponible*. En Linux: `sudo apt install ffmpeg`; en Docker ya viene incluido. |
| Se corta al ver dos canales a la vez | Muchas cuentas IPTV permiten una sola conexión: cierra el otro equipo. |
| Pide la contraseña muchas veces | Revisa que el proxy (nginx/Caddy) reenvíe el encabezado `Authorization` y no guarde en caché las respuestas. |
| La guía (EPG) no aparece | Comprueba que la URL XMLTV abra en el navegador del servidor y que los `tvg-id` de la lista coincidan con los de la guía. |

Para ver qué está pasando, revisa el registro: en la ventana del servidor (Windows), con
`journalctl -u vibe-iptv -f` (Linux) o `docker logs -f vibe-iptv` (Docker). Las líneas
`[proxy]`, `[ffmpeg]` y `[diagnóstico]` explican cada error. **Antes de compartir un registro, borra tu usuario, contraseña y URLs.**
