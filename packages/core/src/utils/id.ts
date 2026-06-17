import { v4 as uuidv4 } from 'uuid';

export function generateId(): string {
  return uuidv4();
}

export function generateDeterministicId(seed: string, namespace: string = 'tactics'): string {
  let hash = 0;
  const str = `${namespace}:${seed}`;
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash = hash & hash;
  }
  return `${namespace}-${Math.abs(hash).toString(16).padStart(8, '0')}`;
}

export function isValidId(id: string): boolean {
  const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const customRegex = /^[a-z]+-[0-9a-f]{8,}$/i;
  return uuidRegex.test(id) || customRegex.test(id);
}

export class IdGenerator {
  private counter: number = 0;
  private prefix: string;

  constructor(prefix: string = 'id') {
    this.prefix = prefix;
  }

  next(): string {
    return `${this.prefix}-${++this.counter}`;
  }

  reset(): void {
    this.counter = 0;
  }
}
