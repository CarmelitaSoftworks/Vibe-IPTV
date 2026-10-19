@echo off
title Vibe IPTV
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Falta Node.js. Instala la version LTS desde https://nodejs.org y vuelve a abrir este archivo.
  pause
  exit /b
)
if not exist "node_modules\ffmpeg-static" (
  echo Instalando ffmpeg para convertir los formatos de los canales de cable. Solo la primera vez...
  call npm install --omit=dev --no-audit --no-fund
)
node server\server.js --open --lan
pause
