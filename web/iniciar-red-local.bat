@echo off
title Vibe IPTV
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Falta Node.js. Instala la version LTS desde https://nodejs.org y vuelve a abrir este archivo. / Node.js is missing: install the LTS version from https://nodejs.org and open this file again.
  pause
  exit /b
)
if not exist "node_modules\tesseract.js-core" (
  echo Instalando ffmpeg y el lector de capturas. Solo la primera vez... / Installing ffmpeg and the screenshot reader. First time only...
  call npm install --omit=dev --no-audit --no-fund
)
node server\server.js --open --lan
pause
