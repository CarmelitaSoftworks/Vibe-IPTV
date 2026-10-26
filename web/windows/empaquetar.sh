#!/usr/bin/env bash
# Arma la versión portable para Windows: un .zip que se descomprime y se abre con doble clic, sin instalar nada.
# Trae Node.js (node.exe), la app web, ffmpeg (para convertir canales que el navegador no reproduce) y el lector de
# capturas. Uso: web/windows/empaquetar.sh [carpeta de salida]   → deja vibe-iptv-windows.zip en esa carpeta.
set -euo pipefail

NODE_VERSION="v24.21.0"            # LTS (septiembre de 2026)
FFMPEG_TAG="b6.0"                  # binarios de ffmpeg-static (los mismos que usa npm)

WEB="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mkdir -p "${1:-$WEB/../dist}" && cd "${1:-$WEB/../dist}" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PKG="$WORK/VibeIPTV"
mkdir -p "$PKG/node" "$PKG/app"

echo "→ Node.js $NODE_VERSION para Windows"
curl -fsSL -o "$WORK/node.zip" "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-win-x64.zip"
curl -fsSL -o "$WORK/SHASUMS256.txt" "https://nodejs.org/dist/$NODE_VERSION/SHASUMS256.txt"
(cd "$WORK" && grep " node-$NODE_VERSION-win-x64.zip\$" SHASUMS256.txt | sed "s/node-$NODE_VERSION-win-x64.zip/node.zip/" | sha256sum -c -)
unzip -q -j "$WORK/node.zip" "node-$NODE_VERSION-win-x64/node.exe" "node-$NODE_VERSION-win-x64/LICENSE" -d "$PKG/node"

echo "→ App web"
# Todo menos pruebas, despliegue en servidores y los lanzadores que piden Node instalado
tar -C "$WEB" --exclude=./test --exclude=./deploy --exclude=./windows --exclude=./node_modules \
  --exclude=./Dockerfile --exclude=./docker-compose.yml --exclude='./iniciar*.bat' -cf - . | tar -C "$PKG/app" -xf -
# Lector de capturas (JavaScript puro: sirve igual en Windows); sin scripts, así no baja el ffmpeg de Linux
# (ffmpeg-static se saca de la copia: su ffmpeg es el del sistema donde se arma, no el de Windows)
(cd "$PKG/app" && rm -f .gitignore .dockerignore package-lock.json \
  && node -e 'const f="package.json",p=JSON.parse(require("fs").readFileSync(f));delete p.optionalDependencies["ffmpeg-static"];require("fs").writeFileSync(f,JSON.stringify(p,null,2)+"\n")' \
  && npm install --omit=dev --ignore-scripts --no-audit --no-fund --no-package-lock --loglevel=error)

echo "→ ffmpeg para Windows"
mkdir -p "$PKG/app/tools"
curl -fsSL -o "$WORK/ffmpeg.gz" "https://github.com/eugeneware/ffmpeg-static/releases/download/$FFMPEG_TAG/ffmpeg-win32-x64.gz"
gunzip -c "$WORK/ffmpeg.gz" > "$PKG/app/tools/ffmpeg.exe"
curl -fsSL -o "$PKG/app/tools/ffmpeg-LICENSE.txt" "https://github.com/eugeneware/ffmpeg-static/releases/download/$FFMPEG_TAG/ffmpeg-win32-x64.LICENSE" || true

echo "→ Lanzadores"
cp "$WEB/windows/Abrir Vibe IPTV.bat" "$WEB/windows/Abrir Vibe IPTV (TV y celulares).bat" "$WEB/windows/LEEME.txt" "$PKG/"
# Fin de línea de Windows en los .bat y el LEEME (con el de Linux, cmd.exe a veces lee mal las líneas)
for f in "$PKG"/*.bat "$PKG/LEEME.txt"; do sed -i 's/\r\?$/\r/' "$f"; done

rm -f "$OUT/vibe-iptv-windows.zip"
(cd "$WORK" && zip -qr -9 "$OUT/vibe-iptv-windows.zip" VibeIPTV)
echo "Listo: $OUT/vibe-iptv-windows.zip ($(du -h "$OUT/vibe-iptv-windows.zip" | cut -f1))"
