package cl.iptv.player.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Enviar del celular a la TV: con Vibe IPTV abierto en los dos y en la misma red Wi-Fi, el celular encuentra la TV
 * (anuncio en la red local, sin configurar nada) y le manda el canal o la película para que la reproduzca.
 * Sólo se aceptan pedidos desde la red de la casa (nunca desde internet).
 */
object Cast {
    const val TYPE = "_vibeiptv._tcp."

    /** Un equipo con Vibe IPTV que recibe videos. */
    data class Target(val name: String, val host: InetAddress, val port: Int)

    // ---------- Lo que se envía ----------
    fun toJson(item: Item, positionMs: Long, from: String) = JSONObject()
        .put("id", item.id).put("kind", item.kind.name).put("name", item.name).put("logo", item.logo).put("group", item.group)
        .put("url", item.url).put("num", item.num ?: -1).put("tvgId", item.tvgId).put("season", item.season).put("episode", item.episode)
        .put("ua", item.userAgent).put("ref", item.referrer).put("seriesName", item.seriesName).put("cat", item.cat).put("sub", item.sub)
        .put("pos", positionMs).put("from", from)

    fun itemFrom(o: JSONObject): Item? {
        val url = o.optString("url")
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("rtmp") && !url.startsWith("rtsp")) return null
        return Item(
            id = o.optString("id").ifBlank { url }, kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.LIVE),
            name = o.optString("name").take(200), logo = o.optString("logo"), group = o.optString("group").ifBlank { "Sin categoría" },
            url = url, num = o.optInt("num", -1).takeIf { it >= 0 }, tvgId = o.optString("tvgId"),
            season = o.optInt("season"), episode = o.optInt("episode"), userAgent = o.optString("ua"), referrer = o.optString("ref"),
            seriesName = o.optString("seriesName"), cat = o.optString("cat"), sub = o.optString("sub"),
        )
    }

    /** Envía al equipo [t]. Lanza error si no responde. */
    fun send(t: Target, item: Item, positionMs: Long) {
        val body = toJson(item, positionMs, Build.MODEL ?: "Celular").toString().toRequestBody("application/json".toMediaType())
        val client = Http.client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
        val host = t.host.hostAddress?.let { if (':' in it) "[$it]" else it } ?: error("Sin dirección")
        client.newCall(Request.Builder().url("http://$host:${t.port}/play").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) error(if (r.code == 403) "La TV tiene apagado \"Recibir videos desde el celular\"" else "La TV respondió ${r.code}")
        }
    }

    // ---------- Pasar las listas a la TV (con código) ----------
    fun sourceToJson(s: Source) = JSONObject().put("id", s.id).put("name", s.name).put("type", s.type.name).put("url", s.url)
        .put("server", s.server).put("username", s.username).put("password", s.password).put("epgUrl", s.epgUrl)

    fun sourceFrom(o: JSONObject): Source? {
        val type = runCatching { SourceType.valueOf(o.optString("type")) }.getOrNull() ?: return null
        val s = Source(o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }, o.optString("name").take(80).ifBlank { "Lista" }, type,
            url = o.optString("url"), server = o.optString("server"), username = o.optString("username"),
            password = o.optString("password"), epgUrl = o.optString("epgUrl"))
        val ok = if (type == SourceType.XTREAM) s.server.isNotBlank() && s.username.isNotBlank() else s.url.startsWith("http")
        return s.takeIf { ok }
    }

    private fun client() = Http.client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    private fun base(t: Target) = "http://${t.host.hostAddress?.let { if (':' in it) "[$it]" else it } ?: error("Sin dirección")}:${t.port}"

    /** Pide a la TV que muestre un código en pantalla. */
    fun pair(t: Target) {
        val body = JSONObject().put("from", Build.MODEL ?: "Celular").toString().toRequestBody("application/json".toMediaType())
        client().newCall(Request.Builder().url("${base(t)}/pair").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) error(if (r.code == 403) "La TV tiene apagado \"Recibir videos desde el celular\"" else "La TV respondió ${r.code}")
        }
    }

    /** Envía las listas con el código que muestra la TV. Devuelve cuántas recibió. */
    fun sendSources(t: Target, code: String, sources: List<Source>): Int {
        val o = JSONObject().put("code", code).put("sources", org.json.JSONArray(sources.map { sourceToJson(it) }))
        client().newCall(Request.Builder().url("${base(t)}/sources").post(o.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (r.code == 401) error("Código incorrecto o vencido: pide uno nuevo")
            if (!r.isSuccessful) error("La TV respondió ${r.code}")
            return r.body?.string()?.trim()?.toIntOrNull() ?: sources.size
        }
    }

    /** Sólo la red de la casa: direcciones privadas, locales o el mismo equipo. */
    fun isLocal(a: InetAddress) = a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
        (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)          // IPv6 privada (fc00::/7)
}

/**
 * Recibe en la TV: un servidor mínimo en la red local que se anuncia para que el celular lo encuentre.
 * Funciona mientras Vibe IPTV está abierto (Android no deja abrir videos desde segundo plano).
 */
class CastReceiver(
    private val ctx: Context, private val name: String, private val onPlay: (Item, Long, String) -> Unit,
    /** Pasar listas: mostrar el código en la TV (null = ocultarlo) y recibir las listas ya validadas. */
    private val onPairCode: (String?, String) -> Unit = { _, _ -> },
    private val onSources: (List<Source>, String) -> Unit = { _, _ -> },
) {
    // Código para pasar listas: 4 cifras, vale 3 minutos y 3 intentos (nadie más en la red puede cargar listas)
    private var code: String? = null
    private var codeUntil = 0L
    private var codeFrom = ""
    private var tries = 0
    private var server: ServerSocket? = null
    private var reg: NsdManager.RegistrationListener? = null
    private val main = Handler(Looper.getMainLooper())

    /** Puerto en que escucha (para las pruebas). */
    val port: Int get() = server?.localPort ?: -1

    fun start() {
        if (server != null) return
        val s = runCatching { ServerSocket(0) }.getOrNull() ?: return
        server = s
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ handle(c) }, "vibe-recibir").apply { isDaemon = true }.start()
            }
        }, "vibe-recibir-servidor").apply { isDaemon = true }.start()
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply { serviceName = name; serviceType = Cast.TYPE; port = s.localPort }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        reg = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun handle(c: Socket) = c.use { sock ->
        sock.soTimeout = 5_000
        val out = sock.getOutputStream()
        fun reply(code: Int, text: String) {
            val b = text.toByteArray()
            out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(b); out.flush()
        }
        if (!Cast.isLocal(sock.inetAddress)) { reply(403, "Sólo desde la red local"); return@use }
        val input = java.io.BufferedInputStream(sock.getInputStream())
        // Encabezados hasta la línea vacía (en bytes: el largo del cuerpo viene en bytes, no en letras)
        val head = java.io.ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < 16_384) {
            val b = input.read(); if (b < 0) break
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break                                   // \r\n\r\n
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val first = lines.firstOrNull() ?: return@use
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val parts = first.split(' ')
        when {
            parts.size >= 2 && parts[0] == "GET" && parts[1] == "/hola" -> reply(200, name)
            parts.size >= 2 && parts[0] == "POST" && parts[1] == "/pair" && length in 0..4_096 -> {
                val o = readJson(input, length)
                val c = synchronized(this) {
                    code = (1000 + java.security.SecureRandom().nextInt(9000)).toString()
                    codeUntil = System.currentTimeMillis() + 180_000; tries = 0
                    codeFrom = o?.optString("from")?.take(60).orEmpty().ifBlank { "otro equipo" }
                    code!!
                }
                main.post { onPairCode(c, codeFrom) }
                main.postDelayed({ synchronized(this) { if (code == c) { code = null; onPairCode(null, "") } } }, 180_000)
                reply(200, "ok")
            }
            parts.size >= 2 && parts[0] == "POST" && parts[1] == "/sources" && length in 1..262_144 -> {
                val o = readJson(input, length)
                val ok = synchronized(this) {
                    val valid = code != null && System.currentTimeMillis() < codeUntil && o?.optString("code") == code
                    if (!valid && code != null && ++tries >= 3) code = null
                    if (valid) code = null
                    valid
                }
                if (!ok) { reply(401, "Código incorrecto"); if (code == null) main.post { onPairCode(null, "") }; return@use }
                val arr = o!!.optJSONArray("sources") ?: org.json.JSONArray()
                val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { j -> Cast.sourceFrom(j) } }
                val from = codeFrom
                main.post { onPairCode(null, ""); onSources(list, from) }
                reply(200, list.size.toString())
            }
            parts.size >= 2 && parts[0] == "POST" && parts[1] == "/play" && length in 1..65_536 -> {
                val o = readJson(input, length)
                val item = o?.let { Cast.itemFrom(it) }
                if (item == null) { reply(400, "Pedido no válido"); return@use }
                val pos = o.optLong("pos").coerceAtLeast(0)
                val from = o.optString("from").take(60)
                main.post { onPlay(item, pos, from) }
                reply(200, "ok")
            }
            else -> reply(404, "No encontrado")
        }
    }

    /** Cancelado en la TV: el código deja de valer. */
    fun cancelPair() { synchronized(this) { code = null } }

    private fun readJson(input: java.io.InputStream, length: Int): JSONObject? {
        if (length <= 0) return null
        val buf = ByteArray(length); var got = 0
        while (got < length) { val n = input.read(buf, got, length - got); if (n < 0) break; got += n }
        return runCatching { JSONObject(String(buf, 0, got, Charsets.UTF_8)) }.getOrNull()
    }

    fun stop() {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        reg?.let { runCatching { nsd.unregisterService(it) } }
        reg = null
        runCatching { server?.close() }
        server = null
    }
}

/** Busca en la red los equipos con Vibe IPTV que reciben videos. [onChange] se llama en el hilo principal. */
class CastFinder(private val ctx: Context, private val ownName: String, private val onChange: (List<Cast.Target>) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Cast.Target>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == ownName) return
                main.post { pending.addLast(i); next() }
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                main.post { if (found.remove(i.serviceName) != null) onChange(found.values.toList()) }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(Cast.TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    /** Android resuelve de a una dirección a la vez: se van resolviendo en fila. */
    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving) return
        val i = pending.removeFirstOrNull() ?: return
        resolving = true
        runCatching {
            nsd.resolveService(i, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) { main.post { resolving = false; next() } }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        s.host?.let { h -> found[s.serviceName] = Cast.Target(s.serviceName, h, s.port); onChange(found.values.toList()) }
                        next()
                    }
                }
            })
        }.onFailure { resolving = false }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
    }
}
