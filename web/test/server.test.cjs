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
