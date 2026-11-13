// Imágenes reales de cada episodio (una escena del capítulo) en vez de la portada repetida o una imagen genérica del
// proveedor. Vienen del catálogo abierto de Stremio (Cinemeta), sin clave. Sólo si la serie se identifica con
// seguridad (mismo año o mismo nombre): mejor la imagen del proveedor que escenas de otra serie.
const fold = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
const STOP = new Set(['el', 'la', 'los', 'las', 'de', 'del', 'the', 'of', 'a', 'and', 'y', 'en', 'un', 'una']);

/** ¿El nombre del catálogo trae las palabras importantes del título? Todas si son 1 o 2; si son más, 2 de cada 3. */
export function sharesWords(title, name) {
  const w = fold(title).split(' ').filter((x) => x.length >= 2 && !STOP.has(x));
  if (!w.length) return false;
  const n = new Set(fold(name).split(' '));
  const hit = w.filter((x) => n.has(x)).length;
  return w.length <= 2 ? hit === w.length : hit * 3 >= w.length * 2;
}

/** La serie del catálogo, sólo si calza: mismo año y nombre, sin año con el nombre, o la primera si es del mismo año. */
export function pickSeries(list, title, year) {
  const yearOk = (m) => !!year && String(m.releaseInfo || m.year || '').startsWith(String(year));
  return list.find((m) => yearOk(m) && sharesWords(title, m.name))
    || (!year && list.find((m) => sharesWords(title, m.name)))
    || (list[0] && yearOk(list[0]) ? list[0] : null);
}

const cache = new Map();
/** { "temporada:episodio": dirección de la imagen }, probando los nombres en orden. Vacío si no se encuentra. */
export async function episodeStills(titles, year) {
  const names = [...new Set(titles.map((t) => String(t || '').trim()).filter((t) => t.length >= 2))];
  const key = `${names.join('|')}#${year}`;
  if (cache.has(key)) return cache.get(key);
  const get = async (url) => { const r = await fetch(url, { signal: AbortSignal.timeout(12000) }); if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); };
  const out = {};
  try {
    let id = null;
    for (const t of names) {
      const r = await get(`https://v3-cinemeta.strem.io/catalog/series/top/search=${encodeURIComponent(t)}.json`);
      const m = pickSeries(r.metas || [], t, year);
      if (m && /^tt/.test(m.imdb_id || m.id)) { id = m.imdb_id || m.id; break; }
    }
    if (id) {
      const meta = (await get(`https://v3-cinemeta.strem.io/meta/series/${id}.json`)).meta || {};
      for (const v of meta.videos || []) {
        const s = Number(v.season), e = Number(v.episode ?? v.number);
        if (!(s > 0 && e > 0) || !/^https?:/.test(v.thumbnail || '')) continue;
        // El catálogo a veces apunta a la temporada equivocada ("0" = especiales): con su servidor de imágenes se
        // arma la dirección con la temporada y el episodio correctos
        out[`${s}:${e}`] = /episodes\.metahub\.space\//.test(v.thumbnail) ? `https://episodes.metahub.space/${id}/${s}/${e}/w780.jpg` : v.thumbnail;
      }
    }
  } catch { /* sin conexión con el catálogo: quedan las imágenes del proveedor */ }
  if (Object.keys(out).length) cache.set(key, out);
  return out;
}
