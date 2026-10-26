import test from 'node:test';
import assert from 'node:assert/strict';
import { parseM3U } from '../js/m3u.js';
import { parseXmltv, parseXmltvTime } from '../js/epg.js';
import { srtToVtt, cleanTitle } from '../js/subtitles.js';
import { normalizeServer } from '../js/xtream.js';
import srv from '../server/server.js';

test('M3U: atributos, comas en comillas, grupos, opciones VLC y tipo', () => {
  const txt = `#EXTM3U x-tvg-url="http://epg.example/guide.xml.gz"
#EXTINF:-1 tvg-id="tvn.cl" tvg-name="TVN" tvg-logo="http://l/tvn.png" group-title="Chile, Nacional",TVN HD
#EXTVLCOPT:http-user-agent=Mozilla/5.0
http://srv/live/u/p/1.ts
#EXTINF:-1,Sin grupo
#EXTGRP:Deportes
http://srv/live/u/p/2.m3u8
#EXTINF:-1 group-title="Cine",Matrix (1999)
http://srv/movie/u/p/99.mkv
http://srv/raw.ts`;
  const r = parseM3U(txt);
  assert.equal(r.epgUrl, 'http://epg.example/guide.xml.gz');
  assert.equal(r.items.length, 4);
  assert.deepEqual([r.items[0].name, r.items[0].group, r.items[0].tvgId, r.items[0].userAgent], ['TVN HD', 'Chile, Nacional', 'tvn.cl', 'Mozilla/5.0']);
  assert.equal(r.items[1].group, 'Deportes');
  assert.equal(r.items[2].kind, 'movie');
  assert.equal(r.items[3].group, 'Sin categoría');
  assert.equal(new Set(r.items.map((i) => i.id)).size, 4);
});

test('XMLTV: tiempos con zona horaria, ventana y búsqueda por nombre', () => {
  assert.equal(parseXmltvTime('20261019120000 +0000'), Date.UTC(2026, 9, 19, 12));
  assert.equal(parseXmltvTime('20261019090000 -0300'), Date.UTC(2026, 9, 19, 12));
  const now = Date.UTC(2026, 9, 19, 12, 30);
  const xml = `<tv><channel id="tvn.cl"><display-name>TVN HD</display-name></channel>
  <programme start="20261019120000 +0000" stop="20261019130000 +0000" channel="tvn.cl"><title lang="es">Noticias &amp; más</title><desc>Desc</desc></programme>
  <programme start="20261019130000 +0000" stop="20261019140000 +0000" channel="tvn.cl"><title><![CDATA[Película]]></title></programme>
  <programme start="20250101000000 +0000" stop="20250101010000 +0000" channel="tvn.cl"><title>Viejo</title></programme></tv>`;
  const epg = parseXmltv(xml, now);
  const nn = epg.nowNext({ tvgId: 'tvn.cl' }, now);
  assert.equal(nn.now.title, 'Noticias & más');
  assert.equal(nn.next.title, 'Película');
  assert.equal(epg.listFor({ tvgId: '', name: 'tvn hd' }).length, 2);
});

test('Subtítulos: SRT a VTT y limpieza de títulos', () => {
  const vtt = srtToVtt('﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nHola\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nChao\r\n');
  assert.ok(vtt.startsWith('WEBVTT'));
  assert.ok(vtt.includes('00:00:01.000 --> 00:00:02.500\nHola'));
  assert.ok(!/^\d+$/m.test(vtt.split('\n\n').slice(1).join('\n').split('\n')[0]));
  assert.equal(cleanTitle('ES| Matrix (1999) [4K] HD'), 'Matrix');
});

test('Xtream: normaliza servidor', () => {
  assert.equal(normalizeServer('dominio.com:8080/'), 'http://dominio.com:8080');
  assert.equal(normalizeServer('https://x.tv/player_api.php?username=a'), 'https://x.tv');
});

test('Proxy: reescritura de m3u8', () => {
  const out = srv.rewriteM3u8('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXTINF:6,\nseg1.ts\nhttps://cdn/x/seg2.ts', 'http://h/a/b/index.m3u8', {}, { ua: '', ref: '' });
  assert.ok(out.includes('URI="/proxy?url=http%3A%2F%2Fh%2Fa%2Fb%2Fkey.bin"'));
  assert.ok(out.includes('/proxy?url=http%3A%2F%2Fh%2Fa%2Fb%2Fseg1.ts'));
  assert.ok(out.includes('/proxy?url=https%3A%2F%2Fcdn%2Fx%2Fseg2.ts'));
});

test('M3U: clasifica TV en vivo / películas / series y agrupa episodios', () => {
  const t = `#EXTM3U
#EXTINF:-1 group-title="Noticias",TVN HD
http://s/live/u/p/1.ts
#EXTINF:-1 group-title="Series | Drama",Breaking Bad S01E02
http://s/x/2.mkv
#EXTINF:-1 group-title="Series | Drama",Breaking Bad S01E01 Piloto
http://s/x/1.mkv
#EXTINF:-1 group-title="Novelas",Pasión de Gavilanes 1x05
http://s/x/p5
#EXTINF:-1 group-title="VOD Acción",Matrix (1999)
http://s/x/m.mp4
#EXTINF:-1 group-title="Estrenos",Dune 2
http://s/x/d
#EXTINF:-1 group-title="Deportes",ESPN 2
http://s/live/9.m3u8`;
  const { items } = parseM3U(t);
  const by = (k) => items.filter((i) => i.kind === k).map((i) => i.name);
  assert.deepEqual(by('live'), ['TVN HD', 'ESPN 2']);
  assert.deepEqual(by('movie'), ['Matrix (1999)', 'Dune 2']);
  assert.deepEqual(by('series'), ['Breaking Bad', 'Pasión de Gavilanes']);
  const bb = items.find((i) => i.name === 'Breaking Bad');
  assert.deepEqual(bb.episodes.map((e) => [e.season, e.episode, e.name]), [[1, 1, 'T1 E1 · Piloto'], [1, 2, 'T1 E2']]);
  assert.equal(bb.episodes[0].seriesName, 'Breaking Bad');
});

test('Grupos: categoría y subcategoría', async () => {
  const { splitGroup } = await import('../js/m3u.js');
  assert.deepEqual(splitGroup('CHILE | DEPORTES'), { cat: 'CHILE', sub: 'DEPORTES' });
  assert.deepEqual(splitGroup('Películas | Acción'), { cat: 'Acción', sub: '' });
  assert.deepEqual(splitGroup('Series » Drama » Turcas'), { cat: 'Drama', sub: 'Turcas' });
  assert.deepEqual(splitGroup(''), { cat: 'Sin categoría', sub: '' });
});

test('guía: calza canales con país, calidad, tildes o mayúsculas distintas', () => {
  const now = Date.UTC(2026, 9, 20, 12);
  const xml = `<tv><channel id="Senal.cl"><display-name>Señal Uno</display-name></channel>
    <programme start="20261020110000 +0000" stop="20261020130000 +0000" channel="Senal.cl"><title>Noticias</title></programme></tv>`;
  const epg = parseXmltv(xml, now);
  for (const name of ['CL: Señal Uno HD', '|CL| Señal Uno FHD', 'Senal Uno 4K'])
    assert.equal(epg.nowNext({ name }, now).now?.title, 'Noticias', name);
  assert.equal(epg.nowNext({ name: 'x', tvgId: 'senal.CL' }, now).now?.title, 'Noticias');
  assert.equal(epg.nowNext({ name: 'Señal Dos HD' }, now).now, null);
});
