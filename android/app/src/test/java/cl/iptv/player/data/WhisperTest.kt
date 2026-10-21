package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Limpieza del texto de Whisper: frases que "alucina" en silencios y marcas de sonido. */
class WhisperTest {
    @Test fun descartaFrasesTipicasDeSilencio() {
        assertEquals("", WhisperCaptioner.clean(" Subtítulos realizados por la comunidad de Amara.org"))
        assertEquals("", WhisperCaptioner.clean(" [Música]"))
        assertEquals("", WhisperCaptioner.clean("♪ ♪"))
    }

    @Test fun conservaElTextoNormal() {
        assertEquals("Buenas noches, comenzamos el noticiero.", WhisperCaptioner.clean(" Buenas noches, (aplausos) comenzamos el noticiero."))
    }
}
