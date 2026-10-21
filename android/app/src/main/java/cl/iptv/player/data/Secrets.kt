package cl.iptv.player.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Contraseñas cifradas con una clave AES-GCM del Android Keystore (la clave nunca sale del equipo).
 * Si el Keystore no funciona en el equipo, se guardan como antes (texto) para no perder el acceso.
 * Los valores cifrados empiezan con "enc1:"; los antiguos sin prefijo se leen tal cual y se cifran al guardar.
 */
object Secrets {
    private const val ALIAS = "vibe_iptv_secretos"
    private const val PREFIX = "enc1:"
    private const val IV = 12

    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey? = cached ?: runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }.getOrNull()?.also { cached = it }

    fun isEncrypted(s: String) = s.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        val k = key() ?: return plain
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    /** Si no se puede descifrar (p. ej. datos restaurados en otro equipo) devuelve "" y hay que volver a escribir la contraseña. */
    fun decrypt(s: String): String {
        if (!isEncrypted(s)) return s
        val k = key() ?: return ""
        return runCatching {
            val b = Base64.decode(s.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, b, 0, IV))
            String(c.doFinal(b, IV, b.size - IV), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
