// Colchón en vivo (igual que en Android): un canal en directo llega al ritmo real, así que lo guardado por adelantado
// no crece solo y cada corte lo gasta. Se reproduce un 2–4 % más lento (el navegador mantiene el tono del audio)
// mientras quede poco, y se vuelve a la velocidad normal al alcanzar la meta.

export const SLOW = 0.98;
export const SLOWER = 0.96;

/** Colchón deseado en segundos según Ajustes → Reproducción → Búfer. */
export function cushionTarget(buffer) {
  return { fast: 4, balanced: 8, stable: 15 }[buffer] || 8;
}

/** Velocidad siguiente según lo guardado por delante (s), la meta (s) y la velocidad actual (con margen). */
export function cushionSpeed(aheadS, targetS, current = 1) {
  if (aheadS < targetS / 3) return SLOWER;
  if (aheadS < targetS * 0.75) return SLOW;
  if (aheadS >= targetS) return 1;
  return current < 1 ? SLOW : 1;
}
