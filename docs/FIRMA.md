# 🔏 Firma de los APK / APK signing

Todas las versiones oficiales de Vibe IPTV se firman con **la misma clave**. Gracias a eso Android
deja **actualizar encima** de la versión anterior sin perder listas, favoritos ni ajustes.

*All official Vibe IPTV builds are signed with the **same key**, so Android lets you **update on top**
of the previous version without losing lists, favorites or settings.*

## Huella del certificado oficial / Official certificate fingerprint

```
SHA-256: B6:02:A8:7A:FD:0D:5A:3A:0B:2D:E9:F2:4C:E3:D4:47:AB:C5:A7:38:4B:EB:FB:10:B7:AD:66:4C:F1:8C:D6:D7
```

Para comprobar un APK / To check an APK: `apksigner verify --print-certs vibe-iptv-armv7.apk`
(o / or `keytool -printcert -jarfile vibe-iptv-armv7.apk`). Si la huella no coincide, **no es una versión oficial**.

## Para quien mantiene el repositorio / For maintainers

La clave **no está en el repositorio**. GitHub Actions la toma de dos secretos
(**Settings → Secrets and variables → Actions → New repository secret**):

| Secreto / Secret | Contenido / Content |
|---|---|
| `VIBE_KEYSTORE_B64` | El archivo `.jks` en base64 / the `.jks` file in base64 (`base64 -w0 vibe.jks`) |
| `VIBE_KEY_PASS` | Contraseña de la clave / key password (alias `vibe`) |

Sin esos secretos la compilación igual funciona, pero firma con una clave de prueba distinta cada vez
(las notas de la versión lo avisan). **Guarda una copia de la clave en un lugar seguro**: si se pierde,
no se pueden publicar actualizaciones y todos tendrían que desinstalar y volver a instalar.

Para compilar firmado en tu computador / To build signed locally:

```bash
VIBE_KEYSTORE=/ruta/vibe.jks VIBE_KEY_PASS='…' ./gradlew assembleRelease
```

Si haces un fork, crea tu propia clave / If you fork, create your own key:
`keytool -genkeypair -keystore vibe.jks -alias vibe -keyalg RSA -keysize 4096 -validity 36500`
