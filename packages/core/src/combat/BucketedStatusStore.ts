import type { StatusEffect } from '../types/combat';
import type { ID } from '../types/common';
import type { Serializable } from '../utils/serialization';
import { serializeMap, serializeSet, deserializeMap, deserializeSet } from '../utils/serialization';

export interface BucketedStatusStoreJSON {
  buckets: Array<{ key: string; value: string[] }>;
  activeTickBuckets: Array<{ key: string; value: string[] }>;
  effectById: Array<{ key: string; value: StatusEffect }>;
  effectTypeIndex: Array<{ key: string; value: string[] }>;
  effectSourceIndex: Array<{ key: string; value: string[] }>;
  effectExpiryTurn: Array<{ key: string; value: number }>;
  effectNextTickTurn: Array<{ key: string; value: number }>;
  currentGlobalTurn: number;
}

export class BucketedStatusStore implements Serializable {
  private buckets: Map<number, Set<ID>>;
  private activeTickBuckets: Map<number, Set<ID>>;
  private effectById: Map<ID, StatusEffect>;
  private effectTypeIndex: Map<string, Set<ID>>;
  private effectSourceIndex: Map<ID, Set<ID>>;
  private effectExpiryTurn: Map<ID, number>;
  private effectNextTickTurn: Map<ID, number>;
  private currentGlobalTurn: number;

  constructor() {
    this.buckets = new Map();
    this.activeTickBuckets = new Map();
    this.effectById = new Map();
    this.effectTypeIndex = new Map();
    this.effectSourceIndex = new Map();
    this.effectExpiryTurn = new Map();
    this.effectNextTickTurn = new Map();
    this.currentGlobalTurn = 0;
  }

  add(effect: StatusEffect): void {
    if (this.effectById.has(effect.id)) {
      this.remove(effect.id);
    }

    this.effectById.set(effect.id, effect);

    const expiryTurn = this.currentGlobalTurn + effect.duration;
    this.effectExpiryTurn.set(effect.id, expiryTurn);
    this.addToBucket(this.buckets, expiryTurn, effect.id);

    const nextTickTurn = this.currentGlobalTurn + 1;
    this.effectNextTickTurn.set(effect.id, nextTickTurn);
    this.addToBucket(this.activeTickBuckets, nextTickTurn, effect.id);

    this.addToBucket(this.effectTypeIndex, effect.type, effect.id);
    this.addToBucket(this.effectSourceIndex, effect.source, effect.id);
  }

  remove(effectId: ID): StatusEffect | undefined {
    const effect = this.effectById.get(effectId);
    if (!effect) return undefined;

    this.effectById.delete(effectId);

    const expiryTurn = this.effectExpiryTurn.get(effectId);
    if (expiryTurn !== undefined) {
      this.removeFromBucket(this.buckets, expiryTurn, effectId);
      this.effectExpiryTurn.delete(effectId);
    }

    const nextTickTurn = this.effectNextTickTurn.get(effectId);
    if (nextTickTurn !== undefined) {
      this.removeFromBucket(this.activeTickBuckets, nextTickTurn, effectId);
      this.effectNextTickTurn.delete(effectId);
    }

    this.removeFromBucket(this.effectTypeIndex, effect.type, effectId);
    this.removeFromBucket(this.effectSourceIndex, effect.source, effectId);

    return effect;
  }

  get(effectId: ID): StatusEffect | undefined {
    const effect = this.effectById.get(effectId);
    if (effect) {
      this.syncEffectDuration(effect);
    }
    return effect;
  }

  has(effectId: ID): boolean {
    return this.effectById.has(effectId);
  }

  private syncEffectDuration(effect: StatusEffect): void {
    const expiryTurn = this.effectExpiryTurn.get(effect.id);
    if (expiryTurn !== undefined) {
      const remaining = Math.max(0, expiryTurn - this.currentGlobalTurn);
      effect.duration = remaining;
      if (typeof effect.durationRemaining === 'number') {
        effect.durationRemaining = remaining;
      }
    }
  }

  getExpiredEffects(): StatusEffect[] {
    return this.getEffectsFromBucket(this.buckets, this.currentGlobalTurn);
  }

  removeExpiredEffects(): StatusEffect[] {
    const expired = this.getExpiredEffects();
    for (const effect of expired) {
      this.remove(effect.id);
    }
    return expired;
  }

  getEffectsToTick(): StatusEffect[] {
    return this.getEffectsFromBucket(this.activeTickBuckets, this.currentGlobalTurn);
  }

  tickCompleted(effectId: ID, nextTickTurn: number): void {
    if (!this.effectById.has(effectId)) return;

    const oldTickTurn = this.effectNextTickTurn.get(effectId);
    if (oldTickTurn !== undefined) {
      this.removeFromBucket(this.activeTickBuckets, oldTickTurn, effectId);
    }

    this.effectNextTickTurn.set(effectId, nextTickTurn);
    this.addToBucket(this.activeTickBuckets, nextTickTurn, effectId);
  }

  updateEffectExpiry(effectId: ID, newDuration: number): void {
    if (!this.effectById.has(effectId)) return;

    const effect = this.effectById.get(effectId)!;
    effect.duration = newDuration;

    const oldExpiryTurn = this.effectExpiryTurn.get(effectId);
    if (oldExpiryTurn !== undefined) {
      this.removeFromBucket(this.buckets, oldExpiryTurn, effectId);
    }

    const newExpiryTurn = this.currentGlobalTurn + newDuration;
    this.effectExpiryTurn.set(effectId, newExpiryTurn);
    this.addToBucket(this.buckets, newExpiryTurn, effectId);
  }

  getAllEffects(): StatusEffect[] {
    const results: StatusEffect[] = [];
    for (const effect of this.effectById.values()) {
      this.syncEffectDuration(effect);
      results.push(effect);
    }
    return results;
  }

  getEffectsByType(type: string): StatusEffect[] {
    const idSet = this.effectTypeIndex.get(type);
    if (!idSet) return [];
    const results: StatusEffect[] = [];
    for (const id of idSet) {
      const effect = this.effectById.get(id);
      if (effect) {
        this.syncEffectDuration(effect);
        results.push(effect);
      }
    }
    return results;
  }

  getEffectsBySource(source: ID): StatusEffect[] {
    const idSet = this.effectSourceIndex.get(source);
    if (!idSet) return [];
    const results: StatusEffect[] = [];
    for (const id of idSet) {
      const effect = this.effectById.get(id);
      if (effect) {
        this.syncEffectDuration(effect);
        results.push(effect);
      }
    }
    return results;
  }

  get count(): number {
    return this.effectById.size;
  }

  advanceTurn(newTurnNumber: number): void {
    this.currentGlobalTurn = newTurnNumber;
  }

  getCurrentTurn(): number {
    return this.currentGlobalTurn;
  }

  importFromArray(effects: StatusEffect[], currentTurn: number): void {
    this.clear();
    this.currentGlobalTurn = currentTurn;

    for (const effect of effects) {
      this.add(effect);
    }
  }

  toArray(): StatusEffect[] {
    return Array.from(this.effectById.values());
  }

  clear(): void {
    this.buckets.clear();
    this.activeTickBuckets.clear();
    this.effectById.clear();
    this.effectTypeIndex.clear();
    this.effectSourceIndex.clear();
    this.effectExpiryTurn.clear();
    this.effectNextTickTurn.clear();
  }

  private addToBucket<K>(map: Map<K, Set<ID>>, key: K, id: ID): void {
    let set = map.get(key);
    if (!set) {
      set = new Set();
      map.set(key, set);
    }
    set.add(id);
  }

  private removeFromBucket<K>(map: Map<K, Set<ID>>, key: K, id: ID): void {
    const set = map.get(key);
    if (!set) return;
    set.delete(id);
    if (set.size === 0) {
      map.delete(key);
    }
  }

  private getEffectsFromBucket(buckets: Map<number, Set<ID>>, turn: number): StatusEffect[] {
    const idSet = buckets.get(turn);
    if (!idSet) return [];
    const results: StatusEffect[] = [];
    for (const id of idSet) {
      const effect = this.effectById.get(id);
      if (effect) results.push(effect);
    }
    return results;
  }

  toJSON(): Record<string, unknown> {
    return {
      buckets: serializeMap(this.buckets, (k: number) => String(k))
        .map(item => ({ key: item.key, value: serializeSet(item.value, v => String(v)) })),
      activeTickBuckets: serializeMap(this.activeTickBuckets, (k: number) => String(k))
        .map(item => ({ key: item.key, value: serializeSet(item.value, v => String(v)) })),
      effectById: serializeMap(this.effectById, (k: ID) => String(k)),
      effectTypeIndex: serializeMap(this.effectTypeIndex, (k: string) => k)
        .map(item => ({ key: item.key, value: serializeSet(item.value, v => String(v)) })),
      effectSourceIndex: serializeMap(this.effectSourceIndex, (k: ID) => String(k))
        .map(item => ({ key: item.key, value: serializeSet(item.value, v => String(v)) })),
      effectExpiryTurn: serializeMap(this.effectExpiryTurn, (k: ID) => String(k)),
      effectNextTickTurn: serializeMap(this.effectNextTickTurn, (k: ID) => String(k)),
      currentGlobalTurn: this.currentGlobalTurn,
    } as unknown as Record<string, unknown>;
  }

  fromJSON(data: Record<string, unknown>): void {
    const d = data as unknown as BucketedStatusStoreJSON;
    this.clear();

    this.currentGlobalTurn = d.currentGlobalTurn ?? 0;

    this.effectById = deserializeMap(d.effectById, k => k);

    for (const item of d.buckets) {
      const turn = parseInt(item.key, 10);
      const ids = deserializeSet(item.value, v => v);
      this.buckets.set(turn, ids);
    }

    for (const item of d.activeTickBuckets) {
      const turn = parseInt(item.key, 10);
      const ids = deserializeSet(item.value, v => v);
      this.activeTickBuckets.set(turn, ids);
    }

    for (const item of d.effectTypeIndex) {
      const ids = deserializeSet(item.value, v => v);
      this.effectTypeIndex.set(item.key, ids);
    }

    for (const item of d.effectSourceIndex) {
      const ids = deserializeSet(item.value, v => v);
      this.effectSourceIndex.set(item.key, ids);
    }

    this.effectExpiryTurn = deserializeMap(d.effectExpiryTurn, k => k);
    this.effectNextTickTurn = deserializeMap(d.effectNextTickTurn, k => k);
  }
}
