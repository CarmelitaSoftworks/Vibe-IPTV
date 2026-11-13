# OkHttp / Media3 traen sus propias reglas. Nada adicional necesario.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Torrents: libtorrent4j llama a sus clases desde código nativo (no se pueden renombrar ni quitar)
-keep class org.libtorrent4j.** { *; }
