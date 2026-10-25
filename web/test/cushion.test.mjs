import test from 'node:test';
import assert from 'node:assert/strict';
import { cushionSpeed, cushionTarget, SLOW, SLOWER } from '../js/cushion.js';

test('Colchón en vivo: más lento con poco guardado, normal al llegar a la meta y con margen', () => {
  assert.equal(cushionSpeed(1, 8), SLOWER);
  assert.equal(cushionSpeed(4, 8), SLOW);
  assert.equal(cushionSpeed(9, 8), 1);
  assert.equal(cushionSpeed(7, 8, SLOW), SLOW);
  assert.equal(cushionSpeed(7, 8, 1), 1);
  assert.equal(cushionTarget('fast'), 4);
  assert.equal(cushionTarget('raro'), 8);
});
