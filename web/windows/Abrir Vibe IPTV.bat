@echo off
chcp 65001 >nul
title Vibe IPTV
cd /d "%~dp0app"
echo Abriendo Vibe IPTV en el navegador... Deja esta ventana abierta mientras lo usas (cerrarla cierra la app).
echo Opening Vibe IPTV in the browser... Keep this window open while you use it (closing it closes the app).
echo.
"%~dp0node\node.exe" server\server.js --open
pause
