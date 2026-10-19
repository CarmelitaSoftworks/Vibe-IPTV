package cl.iptv.player.data

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lee el texto de una captura con ML Kit (modelo incluido en la app: funciona sin Internet
 * y la imagen nunca sale del equipo) y busca los datos de la lista.
 */
object ScreenshotReader {
    suspend fun read(ctx: Context, uri: Uri): ShotDetails {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(ctx, uri) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = recognizer.process(image).await()
            return ScreenshotParser.parse(rows(result))
        } finally {
            recognizer.close()
        }
    }

    /**
     * Rearma las filas de la imagen: ML Kit a veces separa "Usuario" y su valor en bloques distintos
     * (por ejemplo en tablas o paneles a dos columnas). Se unen las líneas que están a la misma altura.
     */
    private fun rows(t: Text): String {
        val lines = t.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        if (lines.isEmpty()) return t.text
        val sorted = lines.sortedBy { it.boundingBox!!.centerY() }
        val out = mutableListOf<MutableList<Text.Line>>()
        for (l in sorted) {
            val b = l.boundingBox!!
            val row = out.lastOrNull()
            val rb = row?.first()?.boundingBox
            if (row != null && rb != null && kotlin.math.abs(b.centerY() - rb.centerY()) < minOf(b.height(), rb.height()) * 0.6f) row += l
            else out += mutableListOf(l)
        }
        return out.joinToString("\n") { r -> r.sortedBy { it.boundingBox!!.left }.joinToString(" ") { it.text } } + "\n" + t.text
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
