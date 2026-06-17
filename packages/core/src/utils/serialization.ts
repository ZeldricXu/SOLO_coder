export function serializeMap<K, V>(map: Map<K, V>, keySerializer: (key: K) => string): Array<{ key: string; value: V }> {
  const result: Array<{ key: string; value: V }> = [];
  for (const [key, value] of map.entries()) {
    result.push({ key: keySerializer(key), value });
  }
  return result;
}

export function deserializeMap<K, V>(
  data: Array<{ key: string; value: V }>,
  keyDeserializer: (key: string) => K
): Map<K, V> {
  const map = new Map<K, V>();
  for (const item of data) {
    map.set(keyDeserializer(item.key), item.value);
  }
  return map;
}

export function serializeSet<T>(set: Set<T>, serializer: (value: T) => string): string[] {
  const result: string[] = [];
  for (const value of set.values()) {
    result.push(serializer(value));
  }
  return result;
}

export function deserializeSet<T>(data: string[], deserializer: (value: string) => T): Set<T> {
  const set = new Set<T>();
  for (const item of data) {
    set.add(deserializer(item));
  }
  return set;
}

export function toJSON<T>(obj: T): string {
  const cache = new WeakMap();
  return JSON.stringify(obj, (key, value) => {
    if (typeof value === 'object' && value !== null) {
      if (cache.has(value)) {
        return '[Circular]';
      }
      cache.set(value, true);
    }
    if (value instanceof Map) {
      return {
        __type__: 'Map',
        data: Array.from(value.entries())
      };
    }
    if (value instanceof Set) {
      return {
        __type__: 'Set',
        data: Array.from(value.values())
      };
    }
    return value;
  }, 2);
}

export function fromJSON<T>(json: string): T {
  return JSON.parse(json, (key, value) => {
    if (value && typeof value === 'object' && value.__type__ === 'Map') {
      return new Map(value.data);
    }
    if (value && typeof value === 'object' && value.__type__ === 'Set') {
      return new Set(value.data);
    }
    return value;
  }) as T;
}

export function deepClone<T>(obj: T, visited: WeakMap<object, object> = new WeakMap()): T {
  if (obj === null || typeof obj !== 'object') {
    return obj;
  }

  if (visited.has(obj as object)) {
    return visited.get(obj as object) as T;
  }
  
  if (obj instanceof Date) {
    const cloned = new Date(obj.getTime()) as unknown as T;
    visited.set(obj as object, cloned as unknown as object);
    return cloned;
  }
  
  if (obj instanceof Map) {
    const cloned = new Map() as unknown as T;
    visited.set(obj as object, cloned as unknown as object);
    for (const [k, v] of obj.entries()) {
      (cloned as unknown as Map<unknown, unknown>).set(deepClone(k, visited), deepClone(v, visited));
    }
    return cloned;
  }
  
  if (obj instanceof Set) {
    const cloned = new Set() as unknown as T;
    visited.set(obj as object, cloned as unknown as object);
    for (const v of obj.values()) {
      (cloned as unknown as Set<unknown>).add(deepClone(v, visited));
    }
    return cloned;
  }
  
  if (Array.isArray(obj)) {
    const cloned: unknown[] = [];
    visited.set(obj as object, cloned);
    for (const item of obj) {
      cloned.push(deepClone(item, visited));
    }
    return cloned as unknown as T;
  }
  
  const cloned = {} as T;
  visited.set(obj as object, cloned as unknown as object);
  for (const key in obj) {
    if (Object.prototype.hasOwnProperty.call(obj, key)) {
      cloned[key] = deepClone(obj[key], visited);
    }
  }
  
  return cloned;
}

export function createChecksum(data: string): string {
  let hash = 0;
  for (let i = 0; i < data.length; i++) {
    const char = data.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash = hash & hash;
  }
  return Math.abs(hash).toString(16).padStart(8, '0');
}

export interface Serializable {
  toJSON(): Record<string, unknown>;
  fromJSON(data: Record<string, unknown>): void;
}
