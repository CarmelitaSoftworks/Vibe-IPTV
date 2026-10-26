@echo off
chcp 65001 >nul
title Vibe IPTV (red local)
cd /d "%~dp0app"
echo Vibe IPTV también se puede abrir desde la TV, el celular o la tablet de tu casa: usa la dirección que aparece abajo.
echo Si Windows pregunta por el Firewall, elige "Redes privadas" y Permitir.
echo Vibe IPTV can also be opened from the TV, phone or tablet at home: use the address shown below.
echo.
"%~dp0node\node.exe" server\server.js --open --lan
pause
