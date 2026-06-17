import { describe, it, expect, beforeEach } from 'vitest';
import {
  clamp,
  lerp,
  smoothstep,
  randomRange,
  chance,
  weightedRandom,
  Random,
} from '../src/utils/math';
import {
  PerlinNoise,
  generateNoiseMap,
  generateFalloffMap,
} from '../src/utils/noise';
import {
  evaluateFormula,
  calculateDamage,
  calculateHitChance,
  calculateElementMultiplier,
} from '../src/utils/formula';
import {
  serializeMap,
  deserializeMap,
  serializeSet,
  deserializeSet,
  deepClone,
  createChecksum,
} from '../src/utils/serialization';
import type { DamageCalculationConfig, ElementChart, ElementType } from '../src/types';

const DEFAULT_DAMAGE_CONFIG: DamageCalculationConfig = {
  baseFormula: 'attack - defense * 0.5',
  critMultiplier: 1.5,
  minDamage: 1,
  maxDamage: 9999,
  elementAdvantageMultiplier: 1.5,
  elementDisadvantageMultiplier: 0.75,
  armorFormula: 'defense * 0.5',
  resistanceFormula: 'resistance',
  terrainBonusFormula: 'terrainBonus',
  heightBonusPerLevel: 0.1,
  directionBackDamageMultiplier: 1.5,
  directionSideDamageMultiplier: 1.2,
  directionFrontDamageMultiplier: 1.0,
};

const DEFAULT_ELEMENT_CHART: ElementChart = {
  fire: { strong: ['grass', 'ice'], weak: ['water'] },
  water: { strong: ['fire', 'earth'], weak: ['electric'] },
  grass: { strong: ['water'], weak: ['fire'] },
  electric: { strong: ['water'], weak: ['ground'] },
  ground: { strong: ['electric'], weak: ['grass'] },
  neutral: { strong: [], weak: [] },
};

describe('math utils', () => {
  it('clamp 限制在范围内', () => {
    expect(clamp(5, 0, 10)).toBe(5);
    expect(clamp(-5, 0, 10)).toBe(0);
    expect(clamp(15, 0, 10)).toBe(10);
    expect(clamp(0, 0, 10)).toBe(0);
    expect(clamp(10, 0, 10)).toBe(10);
  });

  it('lerp 线性插值', () => {
    expect(lerp(0, 10, 0)).toBe(0);
    expect(lerp(0, 10, 1)).toBe(10);
    expect(lerp(0, 10, 0.5)).toBeCloseTo(5);
    expect(lerp(100, 200, 0.3)).toBeCloseTo(130);
  });

  it('smoothstep 平滑插值', () => {
    expect(smoothstep(0, 10, 0)).toBe(0);
    expect(smoothstep(0, 10, 10)).toBe(1);
    expect(smoothstep(0, 10, 5)).toBeCloseTo(0.5, 0.2);
    const mid = smoothstep(0, 10, 5);
    const low = smoothstep(0, 10, 2.5);
    const high = smoothstep(0, 10, 7.5);
    expect(mid).toBeGreaterThan(low);
    expect(high).toBeGreaterThan(mid);
  });

  it('randomRange 范围正确', () => {
    for (let i = 0; i < 100; i++) {
      const v = randomRange(0, 10);
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThanOrEqual(10);
    }
  });

  it('chance 概率事件', () => {
    let alwaysTrue = 0;
    let alwaysFalse = 0;
    for (let i = 0; i < 100; i++) {
      if (chance(100)) alwaysTrue++;
      if (chance(0)) alwaysFalse++;
    }
    expect(alwaysTrue).toBe(100);
    expect(alwaysFalse).toBe(0);
  });

  it('weightedRandom 按权重选择', () => {
    const items = [
      { value: 'a', weight: 100 },
      { value: 'b', weight: 0 },
    ];
    for (let i = 0; i < 50; i++) {
      expect(weightedRandom(items)).toBe('a');
    }
  });

  it('Random 类种子一致性', () => {
    const r1 = new Random(42);
    const r2 = new Random(42);
    const r3 = new Random(99);

    const seq1: number[] = [];
    const seq2: number[] = [];
    const seq3: number[] = [];
    for (let i = 0; i < 20; i++) {
      seq1.push(r1.next());
      seq2.push(r2.next());
      seq3.push(r3.next());
    }

    expect(seq1).toEqual(seq2);
    expect(seq1).not.toEqual(seq3);
  });

  it('Random.range 范围正确', () => {
    const r = new Random(12345);
    for (let i = 0; i < 100; i++) {
      const v = r.range(10, 20);
      expect(v).toBeGreaterThanOrEqual(10);
      expect(v).toBeLessThan(20);
    }
  });
});

describe('noise utils', () => {
  it('PerlinNoise noise2D 范围在 [0,1]', () => {
    const noise = new PerlinNoise(1234);
    for (let x = 0; x < 10; x++) {
      for (let y = 0; y < 10; y++) {
        const v = noise.noise2D(x * 0.1, y * 0.1);
        expect(v).toBeGreaterThanOrEqual(0);
        expect(v).toBeLessThanOrEqual(1);
      }
    }
  });

  it('octaveNoise 相同种子产生相同结果', () => {
    const n1 = new PerlinNoise(777);
    const n2 = new PerlinNoise(777);
    const n3 = new PerlinNoise(778);

    const v1 = n1.octaveNoise2D(0.5, 0.5, 4, 0.5);
    const v2 = n2.octaveNoise2D(0.5, 0.5, 4, 0.5);
    const v3 = n3.octaveNoise2D(0.5, 0.5, 4, 0.5);

    expect(v1).toBeCloseTo(v2);
    expect(v1).not.toBeCloseTo(v3);
  });

  it('generateNoiseMap 尺寸正确', () => {
    const width = 32;
    const height = 24;
    const map = generateNoiseMap(width, height, 1234, 1, 4, 0.5, 2.0);

    expect(map).toHaveLength(height);
    expect(map[0]).toHaveLength(width);

    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        expect(map[y][x]).toBeGreaterThanOrEqual(0);
        expect(map[y][x]).toBeLessThanOrEqual(1);
      }
    }
  });

  it('generateFalloffMap 边缘高中心低', () => {
    const size = 9;
    const falloff = generateFalloffMap(size, size);

    const centerVal = falloff[4][4];
    const edgeVal = falloff[0][0];
    const cornerVal = falloff[8][8];

    expect(centerVal).toBeLessThan(edgeVal);
    expect(centerVal).toBeLessThan(cornerVal);
    expect(falloff[0][4]).toBeGreaterThan(centerVal);
  });

  it('PerlinNoise 小梯度变化平滑', () => {
    const noise = new PerlinNoise(9999);
    const v1 = noise.noise2D(0, 0);
    const v2 = noise.noise2D(0.01, 0);
    const v100 = noise.noise2D(1, 0);

    expect(Math.abs(v1 - v2)).toBeLessThan(0.2);
    expect(typeof v100).toBe('number');
  });
});

describe('formula utils', () => {
  it('evaluateFormula 简单表达式', () => {
    expect(evaluateFormula('1 + 2', {})).toBe(3);
    expect(evaluateFormula('10 - 3', {})).toBe(7);
    expect(evaluateFormula('5 * 4', {})).toBe(20);
    expect(evaluateFormula('20 / 4', {})).toBe(5);
    expect(evaluateFormula('2 ^ 8', {})).toBe(256);
  });

  it('evaluateFormula 优先级和括号', () => {
    expect(evaluateFormula('2 + 3 * 4', {})).toBe(14);
    expect(evaluateFormula('(2 + 3) * 4', {})).toBe(20);
    expect(evaluateFormula('10 / 2 + 3 * 2', {})).toBe(11);
    expect(evaluateFormula('100 - (50 + 25)', {})).toBe(25);
  });

  it('evaluateFormula 变量代入', () => {
    expect(evaluateFormula('attack * 2 - defense', { attack: 30, defense: 10 })).toBe(50);
    expect(evaluateFormula('hp * 0.5', { hp: 200 })).toBeCloseTo(100);
    expect(evaluateFormula('(a + b) / 2', { a: 10, b: 30 })).toBe(20);
  });

  it('calculateDamage 基础伤害计算', () => {
    const cfg: any = {
      baseFormula: 'attack - defense * 0.5',
      critMultiplier: 1.5,
      minDamage: 0,
      maxDamage: 99999,
    };
    const result = calculateDamage(
      cfg,
      50, 20, 0, false, []
    );

    expect(typeof result).toBe('number');
    expect(result).toBeGreaterThan(0);
    expect(result).toBeCloseTo(40);
  });

  it('calculateHitChance 命中率计算', () => {
    expect(calculateHitChance(100, 0)).toBeCloseTo(100);
    expect(calculateHitChance(50, 50)).toBeCloseTo(0);
    expect(calculateHitChance(0, 100)).toBeCloseTo(0);
    const h = calculateHitChance(85, 15);
    expect(h).toBeGreaterThan(0);
    expect(h).toBeLessThanOrEqual(100);
    expect(h).toBeCloseTo(70);
  });

  it('calculateElementMultiplier 元素克制', () => {
    const strong = calculateElementMultiplier('fire' as ElementType, 'grass' as ElementType, DEFAULT_ELEMENT_CHART);
    const weak = calculateElementMultiplier('fire' as ElementType, 'water' as ElementType, DEFAULT_ELEMENT_CHART);
    const neutral = calculateElementMultiplier('fire' as ElementType, 'electric' as ElementType, DEFAULT_ELEMENT_CHART);

    expect(strong).toBeGreaterThan(1);
    expect(weak).toBeLessThan(1);
    expect(weak).toBeGreaterThan(0);
    expect(neutral).toBe(1);
  });
});

describe('serialization utils', () => {
  it('serializeMap / deserializeMap 往返', () => {
    const original = new Map<string, number>([
      ['a', 1],
      ['b', 2],
      ['c', 3],
    ]);

    const serialized = serializeMap(original, k => k);
    const restored = deserializeMap(serialized, k => k as string);

    expect(restored.size).toBe(3);
    expect(restored.get('a')).toBe(1);
    expect(restored.get('b')).toBe(2);
    expect(restored.get('c')).toBe(3);
  });

  it('serializeSet / deserializeSet 往返', () => {
    const original = new Set(['x', 'y', 'z']);

    const serialized = serializeSet(original, v => v);
    const restored = deserializeSet(serialized, v => v);

    expect(restored.size).toBe(3);
    expect(restored.has('x')).toBe(true);
    expect(restored.has('y')).toBe(true);
    expect(restored.has('z')).toBe(true);
  });

  it('deepClone 复杂对象', () => {
    const d = new Date('2024-01-15');
    const original = {
      str: 'hello',
      num: 42,
      arr: [1, 2, { nested: true }],
      map: new Map([['k1', { v: 1 }], ['k2', { v: 2 }]]),
      set: new Set([1, 2, 3]),
      date: d,
      obj: { a: { b: { c: 'deep' } } },
    };

    const cloned = deepClone(original);

    expect(cloned).not.toBe(original);
    expect(cloned.str).toBe(original.str);
    expect(cloned.num).toBe(original.num);
    expect(cloned.arr).toEqual(original.arr);
    expect(cloned.arr).not.toBe(original.arr);
    expect(cloned.map.size).toBe(2);
    expect(cloned.set.has(3)).toBe(true);
    expect(cloned.date.getTime()).toBe(d.getTime());
    expect(cloned.obj.a.b.c).toBe('deep');

    cloned.str = 'modified';
    cloned.arr.push(999);
    expect(original.str).toBe('hello');
    expect(original.arr.length).toBe(3);
  });

  it('createChecksum 稳定哈希', () => {
    const s1 = createChecksum('hello world');
    const s2 = createChecksum('hello world');
    const s3 = createChecksum('hello world!');

    expect(s1).toBe(s2);
    expect(s1).not.toBe(s3);
    expect(typeof s1).toBe('string');
    expect(s1.length).toBeGreaterThan(0);
  });

  it('deepClone 环形引用 Map', () => {
    const m1 = new Map();
    const m2 = new Map();
    m1.set('next', m2);
    m2.set('prev', m1);

    const obj = { map1: m1, map2: m2 };
    const cloned = deepClone(obj);

    expect(cloned.map1).toBeInstanceOf(Map);
    expect(cloned.map2).toBeInstanceOf(Map);
  });
});
