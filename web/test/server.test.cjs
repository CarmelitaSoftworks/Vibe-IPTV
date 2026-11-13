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

test('subtítulos: elige la película correcta del catálogo (automática nunca una cualquiera)', () => {
  const { pickMeta } = require('../server/subtitles.js');
  const e = (name, year, id) => ({ name, year, id });
  assert.equal(pickMeta([e('Inception', '2010', 'tt1'), e('Otra', '2010', 'tt2')], 'El origen', '2010', true).id, 'tt1');
  assert.equal(pickMeta([e('Algo', '2009', 'tt3'), e('Otra', '2010', 'tt2')], 'Pelicula 123', '2010', true), null);
  assert.equal(pickMeta([e('Die Hard', '1988', 'tt4')], 'Duro de matar', '', true), null);
  assert.equal(pickMeta([e('Die Hard', '1988', 'tt4')], 'Duro de matar', '', false).id, 'tt4');
});

test('Torrents: VPN según el adaptador por el que sale internet', () => {
  const { execFileSync } = require('child_process');
  const out = execFileSync(process.execPath, ['-e', `
    const { isVpnIface, vpnIfaceFor } = require('./server/server.js');
    const ifs = { en0: [{ address: '192.168.1.5' }], utun3: [{ address: 'fe80::1' }], wg0: [{ address: '10.8.0.2' }] };
    console.log(JSON.stringify([isVpnIface('wg0'), isVpnIface('tun0'), isVpnIface('OpenVPN Connect'), isVpnIface('Teredo Tunneling Pseudo-Interface'),
      isVpnIface('en0'), isVpnIface('Ethernet'), isVpnIface('Wi-Fi'), vpnIfaceFor('192.168.1.5', ifs), vpnIfaceFor('10.8.0.2', ifs), vpnIfaceFor('1.2.3.4', ifs)]));
    process.exit(0);
  `], { cwd: __dirname + '/..', encoding: 'utf8' });
  assert.deepEqual(JSON.parse(out.trim().split('\n').pop()), [true, true, true, false, false, false, false, 'en0', 'wg0', '']);
});
