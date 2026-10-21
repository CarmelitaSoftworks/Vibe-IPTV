package cl.iptv.player.data

/**
 * Xtream puede repetir el mismo canal en varias categorías con el mismo stream_id. Dos elementos con el mismo id
 * cierran la app en las listas de Compose ("Key was already used"). El primero conserva su id (favoritos y
 * "Seguir viendo" no cambian); los repetidos se numeran como en M3U. Devuelve la lista y cuántos se renombraron.
 */
fun List<Item>.withUniqueIds(): Pair<List<Item>, Int> {
    val seen = HashMap<String, Int>(size * 2)
    val taken = HashSet<String>(size * 2).apply { this@withUniqueIds.forEach { add(it.id) } }
    var renamed = 0
    val out = map { item ->
        val n = seen.getOrDefault(item.id, 0)
        seen[item.id] = n + 1
        if (n == 0) item else {
            var k = n
            var id = "${item.id}_$k"
            while (id in taken) { k++; id = "${item.id}_$k" }
            taken += id
            renamed++
            item.copy(id = id)
        }
    }
    return out to renamed
}
