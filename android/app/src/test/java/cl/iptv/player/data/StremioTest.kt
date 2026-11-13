package cl.iptv.player.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket

/** Extensiones de Stremio contra un servidor de prueba con el mismo formato (datos inventados). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StremioTest {
    private lateinit var server: ServerSocket
    private val base get() = "http://127.0.0.1:${server.localPort}"
    private val pages = mapOf(
        "/manifest.json" to """{"id":"demo","name":"Demo","types":["movie","series"],"idPrefixes":["tt"],
            "resources":["catalog","meta",{"name":"stream","types":["movie","series"]},"subtitles"],
            "catalogs":[{"type":"movie","id":"top","name":"Populares"},{"type":"series","id":"series","name":"Series"},
                        {"type":"movie","id":"buscar","name":"Buscar","extraRequired":["search"]}]}""",
        "/catalog/movie/top.json" to """{"metas":[{"id":"tt1","type":"movie","name":"Película Uno","poster":"http://img/1.jpg","releaseInfo":"2010"}]}""",
        "/catalog/series/series.json" to """{"metas":[{"id":"tt2","type":"series","name":"Serie Dos","poster":"http://img/2.jpg"}]}""",
        "/meta/series/tt2.json" to """{"meta":{"id":"tt2","name":"Serie Dos","videos":[
            {"id":"tt2:1:2","season":1,"episode":2,"name":"Segundo","thumbnail":"http://img/e2.jpg"},
            {"id":"tt2:1:1","season":1,"episode":1,"name":"Primero","thumbnail":"http://img/e1.jpg"}]}}""",
        "/stream/movie/tt1.json" to """{"streams":[
            {"name":"HD","title":"1080p","url":"http://video.ejemplo/1.mp4","behaviorHints":{"proxyHeaders":{"request":{"User-Agent":"UA prueba","Referer":"http://ref.ejemplo/"}}}},
            {"name":"Torrent","infoHash":"abc123"}, {"name":"SD","url":"https://video.ejemplo/1-sd.mp4"}]}""",
        "/subtitles/movie/tt1.json" to """{"subtitles":[{"id":"1","url":"http://subs.ejemplo/es.srt","lang":"spa"}]}""",
    )

    /** Servidor HTTP mínimo: responde la página pedida (o 404). */
    @Before fun start() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    c.use { sock ->
                        val first = sock.getInputStream().bufferedReader().readLine().orEmpty()
                        val path = first.split(' ').getOrNull(1).orEmpty().substringBefore('?')
                        val body = pages[path]
                        val bytes = (body ?: "{}").toByteArray()
                        val out = sock.getOutputStream()
                        out.write("HTTP/1.1 ${if (body != null) "200 OK" else "404 Not Found"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(bytes); out.flush()
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }
    @After fun stop() = server.close()

    @Test fun enlaceMagnet() {
        val m = TorrentStream.magnet("ABC123", listOf("tracker:udp://tracker.ejemplo:1337/announce", "dht:abc123"), "Película")
        assertTrue(m.startsWith("magnet:?xt=urn:btih:abc123&dn=Pel"))
        assertTrue(m.contains("&tr=udp%3A%2F%2Ftracker.ejemplo%3A1337%2Fannounce"))
        assertTrue(!m.contains("dht"))
        assertTrue(TorrentStream.isTorrent(m))
    }

    @Test fun direccionesQueSePegan() {
        assertEquals("https://x.ejemplo/abc", Stremio.normalize("stremio://x.ejemplo/abc/manifest.json"))
        assertEquals("https://x.ejemplo", Stremio.normalize("x.ejemplo/manifest.json"))
        assertEquals(listOf("https://a.ejemplo", "https://b.ejemplo"), Stremio.urls("https://a.ejemplo/manifest.json\nhttps://b.ejemplo/manifest.json, https://a.ejemplo"))
        assertEquals("movie" to "tt1:2:3", Stremio.parse("stm:movie:tt1:2:3"))
    }

    @Test fun catalogosEpisodiosVideosYSubtitulos() = runBlocking {
        val addon = Stremio.manifest("$base/manifest.json")
        assertEquals(2, addon.catalogs.size)                                  // el de sólo búsqueda no se lista
        val items = Stremio.loadAll(listOf(addon))
        val movie = items.first { it.kind == Kind.MOVIE }
        assertEquals("Película Uno (2010)", movie.name)
        assertTrue(Stremio.needsStream(movie))
        val series = items.first { it.kind == Kind.SERIES }
        val eps = Stremio.episodes(listOf(addon), series)
        assertEquals(listOf("T1 E1 · Primero", "T1 E2 · Segundo"), eps.map { it.name })
        assertEquals("http://img/e1.jpg", eps[0].logo)
        val (streams, torrents) = Stremio.streams(listOf(addon), movie)
        assertEquals(2, streams.size); assertEquals(1, torrents)              // el torrent no se usa
        assertEquals("UA prueba", streams[0].userAgent); assertEquals("http://ref.ejemplo/", streams[0].referrer)
        // Con los torrents activados (Ajustes) también aparecen, después de los enlaces directos
        val (withT, skipped) = Stremio.streams(listOf(addon), movie, torrents = true)
        assertEquals(3, withT.size); assertEquals(0, skipped)
        assertTrue(withT.last().torrent && withT.last().url.startsWith("magnet:?xt=urn:btih:abc123"))
        val subs = Stremio.subtitles(listOf(addon), "movie", "tt1")
        assertEquals("spa", subs.single().lang)
    }
}
