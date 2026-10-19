import test from 'node:test';
import assert from 'node:assert/strict';
import { t, useLang } from '../js/i18n.js';

test('Traducción al inglés: exacta, con valores y por partes', () => {
  useLang('es');
  assert.equal(t('Ajustes'), 'Ajustes');
  useLang('en');
  assert.equal(t('Ajustes'), 'Settings');
  assert.equal(t('  Mis listas '), '  My lists ');
  assert.equal(t('1,234 canales'), '1,234 channels');
  assert.equal(t('Motor: VLC · cambiar a MPV'), 'Engine: VLC · switch to MPV');
  assert.equal(t('Guía guardada del 19/10/26, 14:00 · actualizando…'), 'Guide saved on 19/10/26, 14:00 · updating…');
  assert.equal(t('Canal 7 HD'), 'Canal 7 HD');       // nombres propios quedan igual
  useLang('es');
});
