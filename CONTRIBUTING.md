# 🤝 Contribuir a Vibe IPTV / Contributing

¡Gracias por querer ayudar! · Thanks for wanting to help!

## Reglas del proyecto / Project rules
- **Reproductor neutral:** no se aceptan listas, canales, enlaces a contenido ni credenciales.
  *Neutral player: no playlists, channels, content links or credentials.*
- **Privacidad primero:** sin cuentas, anuncios ni telemetría. Nada sale del equipo salvo lo que el usuario pide.
  *Privacy first: no accounts, ads or telemetry.*
- **Español e inglés:** los textos de la interfaz se escriben en español y se traducen en [`i18n/en.json`](i18n/en.json)
  (luego `node i18n/build.mjs`). *UI texts are written in Spanish and translated in `i18n/en.json`.*
- Licencia **GPLv3**: tu aporte se publica bajo la misma licencia. *Contributions are GPLv3.*

## Cómo / How
1. Abre un *issue* antes de cambios grandes. *Open an issue before big changes.*
2. Haz un *fork*, crea una rama y envía un *pull request* con una descripción clara.
3. Web: `cd web && npm test` debe pasar. Android: GitHub Actions compila y corre las pruebas en cada *pull request*.
4. En capturas y registros, **borra usuarios, contraseñas y URLs**. *Remove usernames, passwords and URLs from screenshots and logs.*

## Estructura / Layout
Ver la [Guía de uso](docs/GUIA.md#estructura) · See the [User guide](docs/GUIDE.md#layout).
