import { clamp, lerp, smoothstep } from './math';

export class PerlinNoise {
  private permutation: number[];
  private gradP: Array<{ x: number; y: number }>;

  private static grad3 = [
    { x: 1, y: 1 }, { x: -1, y: 1 }, { x: 1, y: -1 }, { x: -1, y: -1 },
    { x: 1, y: 0 }, { x: -1, y: 0 }, { x: 0, y: 1 }, { x: 0, y: -1 }
  ];

  constructor(seed: number = Date.now()) {
    const p: number[] = [];
    for (let i = 0; i < 256; i++) {
      p[i] = i;
    }

    let n: number;
    let q: number;
    for (let i = 255; i > 0; i--) {
      seed = (seed * 16807) % 2147483647;
      n = seed % (i + 1);
      q = p[i];
      p[i] = p[n];
      p[n] = q;
    }

    this.permutation = new Array(512);
    this.gradP = new Array(512);
    for (let i = 0; i < 512; i++) {
      this.permutation[i] = p[i & 255];
      this.gradP[i] = PerlinNoise.grad3[this.permutation[i] % 8];
    }
  }

  noise2D(x: number, y: number): number {
    let X = Math.floor(x);
    let Y = Math.floor(y);
    x = x - X;
    y = y - Y;
    X = X & 255;
    Y = Y & 255;

    const n00 = this.dotGridGradient(X, Y, x, y);
    const n01 = this.dotGridGradient(X, Y + 1, x, y - 1);
    const n10 = this.dotGridGradient(X + 1, Y, x - 1, y);
    const n11 = this.dotGridGradient(X + 1, Y + 1, x - 1, y - 1);

    const u = this.fade(x);
    const v = this.fade(y);

    const nx0 = lerp(n00, n10, u);
    const nx1 = lerp(n01, n11, u);
    const nxy = lerp(nx0, nx1, v);

    return (nxy + 1) / 2;
  }

  private dotGridGradient(ix: number, iy: number, x: number, y: number): number {
    const gradient = this.gradP[ix + this.permutation[iy]];
    return x * gradient.x + y * gradient.y;
  }

  private fade(t: number): number {
    return t * t * t * (t * (t * 6 - 15) + 10);
  }

  octaveNoise2D(
    x: number,
    y: number,
    octaves: number,
    persistence: number = 0.5,
    lacunarity: number = 2.0
  ): number {
    let value = 0;
    let amplitude = 1;
    let frequency = 1;
    let maxValue = 0;

    for (let i = 0; i < octaves; i++) {
      value += this.noise2D(x * frequency, y * frequency) * amplitude;
      maxValue += amplitude;
      amplitude *= persistence;
      frequency *= lacunarity;
    }

    return value / maxValue;
  }

  fbm(
    x: number,
    y: number,
    octaves: number = 6,
    persistence: number = 0.5,
    lacunarity: number = 2.0
  ): number {
    let value = 0;
    let amplitude = 1;
    let frequency = 1;
    let maxValue = 0;

    for (let i = 0; i < octaves; i++) {
      value += this.noise2D(x * frequency, y * frequency) * amplitude;
      maxValue += amplitude;
      amplitude *= persistence;
      frequency *= lacunarity;
    }

    return value / maxValue;
  }

  ridgedMulti(
    x: number,
    y: number,
    octaves: number = 6,
    persistence: number = 0.5,
    lacunarity: number = 2.0
  ): number {
    let value = 0;
    let amplitude = 1;
    let frequency = 1;
    let maxValue = 0;
    let offset = 1.0;

    for (let i = 0; i < octaves; i++) {
      const noise = Math.abs(this.noise2D(x * frequency, y * frequency) * 2 - 1);
      value += (offset - noise) * amplitude;
      maxValue += amplitude;
      amplitude *= persistence;
      frequency *= lacunarity;
    }

    return value / maxValue;
  }
}

export class ValueNoise {
  private permutation: number[];

  constructor(seed: number = Date.now()) {
    this.permutation = new Array(512);
    let s = seed;
    for (let i = 0; i < 256; i++) {
      s = (s * 1103515245 + 12345) & 0x7fffffff;
      this.permutation[i] = s & 0xff;
    }
    for (let i = 0; i < 256; i++) {
      this.permutation[i + 256] = this.permutation[i];
    }
  }

  noise2D(x: number, y: number): number {
    const xi = Math.floor(x) & 255;
    const yi = Math.floor(y) & 255;
    const xf = x - Math.floor(x);
    const yf = y - Math.floor(y);

    const v00 = this.permutation[xi + this.permutation[yi]] / 255;
    const v10 = this.permutation[(xi + 1) + this.permutation[yi]] / 255;
    const v01 = this.permutation[xi + this.permutation[yi + 1]] / 255;
    const v11 = this.permutation[(xi + 1) + this.permutation[yi + 1]] / 255;

    const u = smoothstep(0, 1, xf);
    const v = smoothstep(0, 1, yf);

    const i1 = lerp(v00, v10, u);
    const i2 = lerp(v01, v11, u);

    return lerp(i1, i2, v);
  }
}

export function generateNoiseMap(
  width: number,
  height: number,
  seed: number,
  scale: number,
  octaves: number,
  persistence: number,
  lacunarity: number,
  offsetX: number = 0,
  offsetY: number = 0,
  normalize: boolean = true
): number[][] {
  const noise = new PerlinNoise(seed);
  const map: number[][] = [];
  let minValue = Infinity;
  let maxValue = -Infinity;

  for (let y = 0; y < height; y++) {
    map[y] = [];
    for (let x = 0; x < width; x++) {
      const sampleX = (x + offsetX) / scale;
      const sampleY = (y + offsetY) / scale;
      const value = noise.octaveNoise2D(sampleX, sampleY, octaves, persistence, lacunarity);
      map[y][x] = value;
      minValue = Math.min(minValue, value);
      maxValue = Math.max(maxValue, value);
    }
  }

  if (normalize && maxValue !== minValue) {
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        map[y][x] = clamp((map[y][x] - minValue) / (maxValue - minValue), 0, 1);
      }
    }
  }

  return map;
}

export function generateFalloffMap(
  width: number,
  height: number,
  strength: number = 3
): number[][] {
  const map: number[][] = [];

  for (let y = 0; y < height; y++) {
    map[y] = [];
    for (let x = 0; x < width; x++) {
      const nx = x / width * 2 - 1;
      const ny = y / height * 2 - 1;
      const value = Math.max(Math.abs(nx), Math.abs(ny));
      map[y][x] = Math.pow(value, strength);
    }
  }

  return map;
}
