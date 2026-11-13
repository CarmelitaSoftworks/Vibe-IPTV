const test = require('node:test');
const assert = require('node:assert/strict');
const s = require('../server/subtitles.js');
test('Subtítulos: Windows-1252, UTF-8 con BOM y ASS', () => {
  assert.equal(s.decodeText(Buffer.from('Niño ¿qué?', 'latin1')), 'Niño ¿qué?');
  assert.equal(s.decodeText(Buffer.from('﻿ñandú', 'utf8')), 'ñandú');
  assert.match(s.assToVtt('Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\i1}Hola\\Nmundo'), /00:00:01\.500 --> 00:00:03\.000\nHola\nmundo/);
});

test('Contraseña opcional (VIBE_PASSWORD)', () => {
  const { execFileSync } = require('child_process');
  const out = execFileSync(process.execPath, ['-e', `
    const { authorized } = require('./server/server.js');
    const h = (p) => ({ headers: { authorization: 'Basic ' + Buffer.from('tv:' + p).toString('base64') } });
    console.log(JSON.stringify([authorized({ headers: {} }), authorized(h('mala')), authorized(h('se:creta'))]));
  `], { cwd: require('path').join(__dirname, '..'), env: { ...process.env, VIBE_PASSWORD: 'se:creta' } }).toString().trim();
  assert.deepEqual(JSON.parse(out.split('\n').pop()), [false, false, true]);
});

test('subtítulos: huella de OpenSubtitles igual a otra implementación y calculada pidiendo sólo 2 pedazos', async () => {
  const subs = require('../server/subtitles.js');
  const head = Buffer.from(Array.from({ length: 65536 }, (_, i) => (i * 7 + 3) % 256));
  const tail = Buffer.from(Array.from({ length: 65536 }, (_, i) => (i * 13 + 5) % 256));
  assert.equal(subs.hashOf(1234567, head, tail), '20a01fa01fb2d687');
  // Archivo de 1.234.567 bytes servido por pedazos (Range), como un servidor de películas
  const file = Buffer.alloc(1234567);
  head.copy(file, 0); tail.copy(file, file.length - 65536);
  const http = require('http');
  let pedidos = 0;
  const srv = http.createServer((req, res) => {
    pedidos++;
    const [a, b] = /bytes=(\d+)-(\d+)/.exec(req.headers.range).slice(1).map(Number);
    res.writeHead(206, { 'Content-Range': `bytes ${a}-${b}/${file.length}`, 'Content-Length': b - a + 1 });
    res.end(file.subarray(a, b + 1));
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  try {
    const fp = await subs.osHash(`http://127.0.0.1:${srv.address().port}/pelicula.mkv`);
    assert.deepEqual(fp, { hash: '20a01fa01fb2d687', size: 1234567 });
    assert.equal(pedidos, 2);
  } finally { srv.close(); }
});
