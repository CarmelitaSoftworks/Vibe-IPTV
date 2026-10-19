# 🖥️ Install Vibe IPTV on your own server

> Back to the [README](../README.en.md) · [User guide](GUIDE.md) · 🇨🇱 [Español](SERVIDOR-PROPIO.md)

This guide is for anyone with **an always-on machine**: a NAS, a Raspberry Pi, a mini PC, a Windows or
Linux PC, or a VPS. It also helps if you run **your own IPTV server** (Tvheadend, Threadfin, xTeVe,
Dispatcharr or similar) and want Vibe IPTV as the player.

Installed this way, every device at home (TV, phones, laptops) opens Vibe IPTV in the browser at an
address like `http://192.168.1.50:8080`, with nothing to install on each one.

---

## What does the server do?

The Vibe IPTV server is a small Node.js program (no extra libraries required) that:

- serves the web app to the devices on your network;
- acts as a **proxy** to your IPTV provider or server, to get around browser blocks (CORS, mixed http/https);
- **converts on the fly** with ffmpeg the channels the browser can't play (AC-3/MP2 audio, MPEG-2 video);
- searches and downloads **subtitles** from OpenSubtitles, SubDL and Podnapisi.

> [!WARNING]
> Because it forwards connections, **don't publish it on the Internet without a password**. Read the
> [Access from outside your home](#-access-from-outside-your-home) section.

**Requirements:** any machine with 512 MB of free RAM is enough. ffmpeg conversion does use CPU:
a Raspberry Pi 4 can convert one channel at a time; a mini PC or x86 NAS has no trouble.

---

## Option A · Docker (recommended for NAS and servers)

Works on any Linux with Docker, and on NAS devices with container support (Synology Container
Manager, QNAP Container Station, Unraid, TrueNAS, CasaOS, Portainer…). The image already includes ffmpeg.

```bash
git clone https://github.com/CarmelitaSoftworks/Vibe-IPTV.git
cd Vibe-IPTV/web
docker compose up -d
```

Open `http://SERVER-IP:8080` from any device on your network.

To change the port or set a password, edit `web/docker-compose.yml`:

```yaml
    ports:
      - "9000:8080"            # now it would be http://IP:9000
    environment:
      - TZ=America/Santiago
      - VIBE_PASSWORD=a-long-password
```

and apply the changes with `docker compose up -d`.

**Update to a new version:**
```bash
cd Vibe-IPTV && git pull
cd web && docker compose up -d --build
```

> On NAS devices with a graphical interface: create a new “project” or “stack”, point it at the `web/`
> folder of the downloaded repository and paste the contents of `docker-compose.yml`.

---

## Option B · Linux or Raspberry Pi as a service (no Docker)

### 1. Install Node.js (18 or newer), ffmpeg and git
```bash
sudo apt update
sudo apt install -y nodejs ffmpeg git
node --version     # should print v18 or higher
```
If your distribution ships an older Node.js, install it from [nodejs.org](https://nodejs.org) or with [NodeSource](https://github.com/nodesource/distributions).

### 2. Download Vibe IPTV
```bash
sudo useradd --system --home /opt/vibe-iptv --shell /usr/sbin/nologin vibe
sudo git clone https://github.com/CarmelitaSoftworks/Vibe-IPTV.git /opt/vibe-iptv
sudo chown -R vibe:vibe /opt/vibe-iptv
```

### 3. Try it by hand
```bash
cd /opt/vibe-iptv/web
HOST=0.0.0.0 node server/server.js
```
It should print your network address (for example `http://192.168.1.50:8080`) and
`Format conversion (ffmpeg): active.` (messages follow the system language; force English with `VIBE_LANG=en`). Open it from another device, then stop the server with `Ctrl + C`.

### 4. Install it as a service (starts on boot)
```bash
sudo cp /opt/vibe-iptv/web/deploy/vibe-iptv.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now vibe-iptv
systemctl status vibe-iptv          # should say "active (running)"
journalctl -u vibe-iptv -f          # follow the log live
```
To set a password or change the port, edit the `Environment=` lines of the service file
(`sudo systemctl edit --full vibe-iptv`) and restart it with `sudo systemctl restart vibe-iptv`.

**Update:**
```bash
cd /opt/vibe-iptv && sudo -u vibe git pull && sudo systemctl restart vibe-iptv
```

---

## Option C · Always-on Windows PC

1. Install [Node.js](https://nodejs.org) (LTS version) and download the repository (**Code → Download ZIP**). Unzip it, for example, to `C:\VibeIPTV`.
2. Double-click `web\iniciar-red-local.bat`. The first time it installs ffmpeg automatically and shows the address for your other devices.
3. If Windows asks about the **Firewall**, allow access on **private networks**.

**To start it automatically when the PC boots** (without leaving a window open):

1. Open **Task Scheduler** → **Create Task…**
2. *General* tab: name `Vibe IPTV`, check **“Run whether user is logged on or not”**.
3. *Triggers* tab: **New… → At startup**.
4. *Actions* tab: **New… → Start a program**
   - Program: `C:\Program Files\nodejs\node.exe`
   - Arguments: `server\server.js --lan`
   - Start in: `C:\VibeIPTV\web`
5. *Settings* tab: uncheck **“Stop the task if it runs longer than…”**.

To use a password, create a system environment variable `VIBE_PASSWORD` (Control Panel → System →
Advanced system settings → Environment Variables) and restart the task.

---

## Opening it on the TV, phone and other devices

- Use the address the server prints at startup: `http://SERVER-IP:8080`.
- It's best if the server always keeps **the same IP**: reserve it in your router (*static DHCP* or *IP reservation*).
- On the phone or TV, open the browser menu → **Add to Home screen** for a shortcut.
- Browsers only offer full app (PWA) installation over **HTTPS**; at home the shortcut works just as well.
- On **Android and Android TV** you can also use the **native app** (APK), which doesn't need this server: it connects straight to your list and plays with ExoPlayer, VLC or MPV.

---

## 📡 Using your own IPTV server as the source

If you serve the channels yourself (DVB tuner, set-top box, your operator's IPTV re-organized, etc.),
add an **M3U list by URL** in Vibe IPTV with your server's address and, under Settings → Lists & guide,
the address of its **XMLTV guide**. Common addresses (they may vary with your setup):

| Server | M3U list | XMLTV guide |
|---|---|---|
| **Tvheadend** | `http://IP:9981/playlist/channels.m3u` | `http://IP:9981/xmltv/channels` |
| **Threadfin** | `http://IP:34400/m3u/threadfin.m3u` | `http://IP:34400/xmltv/threadfin.xml` |
| **xTeVe** | `http://IP:34400/m3u/xteve.m3u` | `http://IP:34400/xmltv/xteve.xml` |
| **Dispatcharr** and others | The M3U URL shown in its dashboard | The EPG/XMLTV URL shown in its dashboard |
| **Xtream Codes**-compatible panel | Use the *Xtream Codes* option with server, username and password | Set up automatically |

Tips:
- Use the server's **IP** (for example `192.168.1.20`) instead of names like `server.local`, which some TVs can't resolve.
- If your server asks for a username and password, you can put them in the URL: `http://user:password@IP:9981/playlist/channels.m3u`.
  In Tvheadend, another option is to create a streaming-only user or enable *ticket*/*persistent auth* access.
- If channels come in **MPEG-2 or AC-3** (typical of DVB-T and cable), the browser can still watch them thanks to the Vibe IPTV server's ffmpeg conversion.
  On Android, the VLC or MPV engine plays them directly. If your server has transcoding profiles (for example `?profile=pass` or an H.264 profile in Tvheadend), you can use those too.
- If Vibe IPTV and your IPTV server run **on the same machine**, still use the network IP (not `localhost`), so the same list works on the TV and the phone.

---

## 🌍 Access from outside your home

Recommended, from most to least secure:

1. **Private VPN** (simplest and safest): install [Tailscale](https://tailscale.com) or [WireGuard](https://www.wireguard.com) on the server and on your phone. You open `http://TAILSCALE-IP:8080` as if you were at home, without opening router ports.
2. **Domain with HTTPS + password**: if you really need to publish it,
   - always set `VIBE_PASSWORD` (the browser asks for it only once);
   - put it behind an HTTPS proxy. There are ready-made examples in [`web/deploy/Caddyfile`](../web/deploy/Caddyfile) (automatic certificate) and [`web/deploy/nginx.conf`](../web/deploy/nginx.conf);
   - only forward port 443 on your router to the proxy, **never port 8080 directly**.

> [!CAUTION]
> Without a password, anyone who finds your address could use your server and your connection as a
> relay. Also, many IPTV providers limit simultaneous connections per account.

---

## ⚙️ Server options

| Variable | What it does | Default |
|---|---|---|
| `PORT` | Port it listens on | `8080` |
| `HOST` | `0.0.0.0` to accept other devices on the network; `127.0.0.1` this machine only | `127.0.0.1` (Docker: `0.0.0.0`) |
| `VIBE_PASSWORD` | Access password (any username + this password) | no password |
| `FFMPEG_PATH` | Path to ffmpeg if it isn't found automatically | detected automatically |
| `IPTV_UA` | User-Agent used to request channels | `VLC/3.0.21 LibVLC/3.0.21` |
| `VIBE_LANG` | Language of the console messages (`es` or `en`) | system language |

It also accepts the `--lan` (same as `HOST=0.0.0.0`) and `--open` (opens the browser on start) options.

---

## 🩺 Common problems

| Symptom | What to check |
|---|---|
| The page doesn't open from another device | The server must start with `HOST=0.0.0.0` (or `--lan`); check the server's firewall (port 8080, private network) and that both devices are on the same network. |
| `Port 8080 is already in use` | Another copy is running or another program uses that port: change `PORT`. |
| Some channels don't play in the browser | In Settings → Playback, *Conversion (ffmpeg)* should say *Available*. On Linux: `sudo apt install ffmpeg`; Docker already includes it. |
| It cuts out when watching two channels at once | Many IPTV accounts allow a single connection: close the other device. |
| It keeps asking for the password | Check that the proxy (nginx/Caddy) forwards the `Authorization` header and doesn't cache responses. |
| The guide (EPG) doesn't show | Check that the XMLTV URL opens in the server's browser and that the list's `tvg-id`s match the guide's. |

To see what's going on, check the log: in the server window (Windows), with
`journalctl -u vibe-iptv -f` (Linux) or `docker logs -f vibe-iptv` (Docker). The
`[proxy]`, `[ffmpeg]` and `[diagnóstico]` lines explain each error. **Before sharing a log, remove your username, password and URLs.**
