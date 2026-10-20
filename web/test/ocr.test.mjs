import test from 'node:test';
import assert from 'node:assert/strict';
import { parseListDetails as p } from '../js/ocr-parse.js';

test('Captura: mensaje típico de un proveedor (Xtream)', () => {
  const r = p(`✅ Datos de tu cuenta
Usuario: juan123
Contraseña: Ab9xK2
URL del servidor: http://ejemplo-tv.com:8080
Vence: 12/12/2026
Conexiones: 1`);
  assert.equal(r.type, 'xtream');
  assert.equal(r.server, 'http://ejemplo-tv.com:8080');
  assert.equal(r.username, 'juan123');
  assert.equal(r.password, 'Ab9xK2');
});

test('Captura: etiquetas en inglés con valor en la línea siguiente y puerto aparte', () => {
  const r = p(`Your IPTV subscription
Username
maria_88
Password
Zz77qq
Host
line.provider.net
Port: 2095`);
  assert.equal(r.type, 'xtream');
  assert.equal(r.server, 'http://line.provider.net:2095');
  assert.equal(r.username, 'maria_88');
});

test('Captura: enlace M3U get.php con credenciales y errores de lectura', () => {
  const r = p('Lista M3U: h t t p ; / / tv.ejemplo.cl:80/get.php?username=ana&password=xyz&type=m3u_plus&output=ts\nEPG: http://tv.ejemplo.cl:80/xmltv.php?username=ana&password=xyz');
  assert.equal(r.type, 'xtream');
  assert.equal(r.server, 'http://tv.ejemplo.cl');
  assert.equal(r.username, 'ana');
  assert.equal(r.password, 'xyz');
  assert.match(r.url, /get\.php/);
  assert.match(r.epgUrl, /xmltv\.php/);
});

test('Captura: sólo un enlace .m3u8', () => {
  const r = p('Mi lista\nhttps://cdn.ejemplo.org/listas/chile.m3u8');
  assert.equal(r.type, 'm3u');
  assert.equal(r.url, 'https://cdn.ejemplo.org/listas/chile.m3u8');
});

test('Captura: texto sin datos de una lista', () => {
  const r = p('Hola, ¿cómo estás?\nNos vemos mañana');
  assert.equal(r.type, null);
  assert.deepEqual(r.found, []);
});

test('Captura: "Server status" no se confunde con el servidor', () => {
  const r = p('Server status: online\nUser: pepe\nPass: 1234\nDNS: http://iptv.ejemplo.com:25461');
  assert.equal(r.server, 'http://iptv.ejemplo.com:25461');
  assert.equal(r.type, 'xtream');
});

test('Captura: viñetas leídas como » o • antes de cada dato', () => {
  const r = p('jHola! Aqui estan tus datos\n» Usuario: carlos2026\n» Contrasena: Tq7Lm2xP\nURL: http://demo-iptv.example.com:8080\n1) Vence: 30/11/2026');
  assert.equal(r.type, 'xtream');
  assert.equal(r.username, 'carlos2026');
  assert.equal(r.password, 'Tq7Lm2xP');
});

test('Correo con etiquetas bilingües y enlace cortado en varias líneas', () => {
  const r = p(`Datos de acceso\nUsuario / Username\n11112222\nContraseña / Password\n33334444\nURL\nhttp://tv.ejemplo.pro:8080\nImportante: El usuario y contraseña debe ser ingresado tal como aparece\nM3u Plus Playlist URL: http://tv.ejemplo.pro:8\n080/get.php?username=11112222&pass\nword=33334444&type=m3u_plus&output\n=mpegts`);
  assert.equal(r.type, 'xtream');
  assert.equal(r.server, 'http://tv.ejemplo.pro:8080');
  assert.equal(r.username, '11112222');
  assert.equal(r.password, '33334444');
  assert.match(r.url, /get\.php\?username=11112222&password=33334444&type=m3u_plus&output=mpegts$/);
});

test('Etiqueta bilingüe con el valor en la misma línea', () => {
  const r = p('Usuario / Username: pepe\nContraseña / Password: Xy12\nURL: http://srv.ejemplo.net:2095');
  assert.equal(r.username, 'pepe');
  assert.equal(r.password, 'Xy12');
  assert.equal(r.server, 'http://srv.ejemplo.net:2095');
});
