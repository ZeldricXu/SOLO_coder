export const clamp = (value: number, min: number, max: number): number => {
  return Math.max(min, Math.min(max, value));
};

export const lerp = (a: number, b: number, t: number): number => {
  return a + (b - a) * t;
};

export const lerp2D = (a: { x: number; y: number }, b: { x: number; y: number }, t: number) => ({
  x: lerp(a.x, b.x, t),
  y: lerp(a.y, b.y, t),
});

export const lerp3D = (a: { x: number; y: number; z: number }, b: { x: number; y: number; z: number }, t: number) => ({
  x: lerp(a.x, b.x, t),
  y: lerp(a.y, b.y, t),
  z: lerp(a.z, b.z, t),
});

export const degToRad = (deg: number): number => (deg * Math.PI) / 180;

export const radToDeg = (rad: number): number => (rad * 180) / Math.PI;

export const roundTo = (value: number, decimals: number): number => {
  const factor = Math.pow(10, decimals);
  return Math.round(value * factor) / factor;
};

export const formatNumber = (value: number, decimals: number = 2): string => {
  return roundTo(value, decimals).toFixed(decimals);
};

export const formatArea = (area: number, units: 'metric' | 'imperial' = 'metric'): string => {
  if (units === 'imperial') {
    const sqFt = area * 10.7639;
    return `${formatNumber(sqFt, 2)} ft²`;
  }
  return `${formatNumber(area, 2)} m²`;
};

export const formatLength = (length: number, units: 'metric' | 'imperial' = 'metric'): string => {
  if (units === 'imperial') {
    const feet = length * 3.28084;
    const wholeFeet = Math.floor(feet);
    const inches = Math.round((feet - wholeFeet) * 12);
    return inches >= 12 ? `${wholeFeet + 1}' 0"` : `${wholeFeet}' ${inches}"`;
  }
  if (length >= 1) {
    return `${formatNumber(length, 2)} m`;
  }
  return `${formatNumber(length * 100, 0)} cm`;
};

export const formatVolume = (volume: number, units: 'metric' | 'imperial' = 'metric'): string => {
  if (units === 'imperial') {
    const cuFt = volume * 35.3147;
    return `${formatNumber(cuFt, 2)} ft³`;
  }
  return `${formatNumber(volume, 2)} m³`;
};

export const formatPrice = (price: number, currency: string = 'CNY'): string => {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency,
  }).format(price);
};

export const smoothStep = (edge0: number, edge1: number, x: number): number => {
  const t = clamp((x - edge0) / (edge1 - edge0), 0, 1);
  return t * t * (3 - 2 * t);
};

export const easeInOutQuad = (t: number): number => {
  return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
};

export const easeOutCubic = (t: number): number => {
  return 1 - Math.pow(1 - t, 3);
};

export const randomRange = (min: number, max: number): number => {
  return Math.random() * (max - min) + min;
};

export const almostEqual = (a: number, b: number, epsilon: number = 0.0001): boolean => {
  return Math.abs(a - b) < epsilon;
};
