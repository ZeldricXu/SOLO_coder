import { UserSegment, FlagEvaluationContext } from './types';
import { generateId, currentDateTime, logger } from '../utils/common';

export class SegmentManager {
  private segments: Map<string, UserSegment> = new Map();

  createSegment(name: string, conditions: UserSegment['conditions'], description?: string): UserSegment {
    const segment: UserSegment = {
      id: generateId('seg_'),
      name,
      description,
      conditions,
    };

    this.segments.set(segment.id, segment);
    logger.info(`User segment created`, { id: segment.id, name });
    return segment;
  }

  getSegment(id: string): UserSegment | undefined {
    return this.segments.get(id);
  }

  updateSegment(id: string, updates: Partial<UserSegment>): UserSegment | undefined {
    const segment = this.segments.get(id);
    if (!segment) return undefined;

    const updated: UserSegment = { ...segment, ...updates };
    this.segments.set(id, updated);
    logger.info(`User segment updated`, { id });
    return updated;
  }

  deleteSegment(id: string): boolean {
    return this.segments.delete(id);
  }

  listSegments(): UserSegment[] {
    return Array.from(this.segments.values());
  }

  userInSegment(context: FlagEvaluationContext, segment: UserSegment): boolean {
    for (const condition of segment.conditions) {
      const value = this.getNestedValue(context.attributes, condition.field);

      if (!this.evaluateCondition(value, condition.operator, condition.value)) {
        return false;
      }
    }
    return true;
  }

  getMatchingSegments(context: FlagEvaluationContext): UserSegment[] {
    return Array.from(this.segments.values()).filter(segment =>
      this.userInSegment(context, segment)
    );
  }

  private getNestedValue(obj: Record<string, unknown>, path: string): unknown {
    const keys = path.split('.');
    let result: unknown = obj;
    for (const key of keys) {
      if (result && typeof result === 'object' && result !== null) {
        result = (result as Record<string, unknown>)[key];
      } else {
        return undefined;
      }
    }
    return result;
  }

  private evaluateCondition(value: unknown, operator: string, expected: unknown): boolean {
    switch (operator) {
      case 'eq':
        return value === expected;
      case 'ne':
        return value !== expected;
      case 'gt':
        return typeof value === 'number' && typeof expected === 'number' && value > expected;
      case 'lt':
        return typeof value === 'number' && typeof expected === 'number' && value < expected;
      case 'gte':
        return typeof value === 'number' && typeof expected === 'number' && value >= expected;
      case 'lte':
        return typeof value === 'number' && typeof expected === 'number' && value <= expected;
      case 'in':
        return Array.isArray(expected) && expected.includes(value);
      case 'not_in':
        return Array.isArray(expected) && !expected.includes(value);
      case 'contains':
        return typeof value === 'string' && typeof expected === 'string' && value.includes(expected);
      case 'regex':
        return typeof value === 'string' && typeof expected === 'string' && new RegExp(expected).test(value);
      default:
        return false;
    }
  }
}

export const segmentManager = new SegmentManager();
