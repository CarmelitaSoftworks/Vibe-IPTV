package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodersTest {
    @Test fun primeroElDelChipYElSoftwareDeRespaldo() {
        val lista = listOf("c2.android.avc.decoder", "OMX.amlogic.avc.decoder.awesome", "OMX.google.h264.decoder", "c2.amlogic.avc.decoder")
        val orden = Decoders.hardwareFirst(lista) { !Decoders.isSoftwareName(it) }
        assertEquals(listOf("OMX.amlogic.avc.decoder.awesome", "c2.amlogic.avc.decoder", "c2.android.avc.decoder", "OMX.google.h264.decoder"), orden)
    }

    @Test fun sinChipQuedaElDeSoftware() {
        assertEquals(listOf("c2.android.mpeg2.decoder"), Decoders.hardwareFirst(listOf("c2.android.mpeg2.decoder")) { !Decoders.isSoftwareName(it) })
    }

    @Test fun reconoceLosDeSoftware() {
        assertTrue(Decoders.isSoftwareName("OMX.google.mpeg2.decoder"))
        assertTrue(Decoders.isSoftwareName("c2.android.hevc.decoder"))
        assertFalse(Decoders.isSoftwareName("OMX.MTK.VIDEO.DECODER.AVC"))
        assertFalse(Decoders.isSoftwareName("c2.amlogic.hevc.decoder"))
    }
}
