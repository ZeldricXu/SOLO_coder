import { FeatureFlag, FlagEvaluationContext, FlagEvaluationResult, FrequencyLimitConfig } from './types';
import { segmentManager } from './segmentManager';
import { rolloutManager } from './rolloutManager';
import { generateId, currentDateTime, logger } from '../utils/common';

export class FeatureFlagManager {
  private flags: Map<string, FeatureFlag> = new Map();
  private keyIndex: Map<string, string> = new Map();
  private requestCounts: Map<string, { count: number; windowStart: number }> = new Map();

  createFlag(params: Omit<FeatureFlag, 'id' | 'createdAt' | 'updatedAt'>): FeatureFlag {
    const now = currentDateTime();
    const flag: FeatureFlag = {
      ...params,
      id: generateId('flag_'),
      createdAt: now,
      updatedAt: now,
    };

    this.flags.set(flag.id, flag);
    this.keyIndex.set(flag.key, flag.id);
    logger.info(`Feature flag created`, { id: flag.id, key: flag.key });
    return flag;
  }

  getFlag(id: string): FeatureFlag | undefined {
    return this.flags.get(id);
  }

  getFlagByKey(key: string): FeatureFlag | undefined {
    const id = this.keyIndex.get(key);
    return id ? this.flags.get(id) : undefined;
  }

  updateFlag(id: string, updates: Partial<FeatureFlag>): FeatureFlag | undefined {
    const flag = this.flags.get(id);
    if (!flag) return undefined;

    if (updates.key && updates.key !== flag.key) {
      this.keyIndex.delete(flag.key);
      this.keyIndex.set(updates.key, id);
    }

    const updated: FeatureFlag = {
      ...flag,
      ...updates,
      updatedAt: currentDateTime(),
    };

    this.flags.set(id, updated);
    logger.info(`Feature flag updated`, { id });
    return updated;
  }

  deleteFlag(id: string): boolean {
    const flag = this.flags.get(id);
    if (flag) {
      this.keyIndex.delete(flag.key);
    }
    return this.flags.delete(id);
  }

  listFlags(tags?: string[], environment?: string): FeatureFlag[] {
    let flags = Array.from(this.flags.values());

    if (environment) {
      flags = flags.filter(f => f.environment === environment);
    }

    if (tags && tags.length > 0) {
      flags = flags.filter(f => tags.some(tag => f.tags.includes(tag)));
    }

    return flags;
  }

  evaluate<T = unknown>(
    key: string,
    context: FlagEvaluationContext,
    frequencyConfig?: FrequencyLimitConfig
  ): FlagEvaluationResult<T> {
    const flag = this.getFlagByKey(key);

    if (!flag) {
      logger.warn(`Feature flag not found`, { key });
      return {
        key,
        value: undefined as unknown as T,
        enabled: false,
        reason: 'default_value',
      };
    }

    if (frequencyConfig && !this.checkFrequencyLimit(context, frequencyConfig)) {
      return {
        key,
        value: flag.defaultValue as T,
        enabled: false,
        reason: 'default_value',
        metadata: { frequencyLimited: true },
      };
    }

    if (!flag.enabled) {
      return {
        key,
        value: flag.defaultValue as T,
        enabled: false,
        reason: 'disabled',
      };
    }

    if (context.environment !== flag.environment) {
      return {
        key,
        value: flag.defaultValue as T,
        enabled: false,
        reason: 'default_value',
      };
    }

    if (flag.targetSegments.length > 0) {
      const matchingSegments = segmentManager.getMatchingSegments(context);
      const inTargetSegment = matchingSegments.some(s => flag.targetSegments.includes(s.id));

      if (!inTargetSegment) {
        return {
          key,
          value: flag.defaultValue as T,
          enabled: false,
          reason: 'default_value',
        };
      }
    }

    if (flag.rollout) {
      const currentPercentage = rolloutManager.calculateCurrentPercentage(flag.rollout);
      if (!rolloutManager.shouldRolloutToUser(flag, context, currentPercentage)) {
        return {
          key,
          value: flag.defaultValue as T,
          enabled: false,
          reason: 'rollout_percentage',
          metadata: { currentPercentage },
        };
      }
    }

    return {
      key,
      value: flag.value as T,
      enabled: true,
      reason: 'enabled',
    };
  }

  evaluateAll(context: FlagEvaluationContext): FlagEvaluationResult[] {
    return Array.from(this.flags.values())
      .filter(f => f.environment === context.environment)
      .map(flag => this.evaluate(flag.key, context));
  }

  private checkFrequencyLimit(context: FlagEvaluationContext, config: FrequencyLimitConfig): boolean {
    const key = config.keyGenerator
      ? config.keyGenerator(context)
      : context.userId || context.sessionId || 'global';

    const now = Date.now();
    const counter = this.requestCounts.get(key) || { count: 0, windowStart: now };

    if (now - counter.windowStart > config.windowMs) {
      counter.count = 0;
      counter.windowStart = now;
    }

    if (counter.count >= config.maxRequests) {
      return false;
    }

    counter.count++;
    this.requestCounts.set(key, counter);
    return true;
  }

  getRolloutStatus(key: string) {
    const flag = this.getFlagByKey(key);
    if (!flag) return null;
    return rolloutManager.getRolloutStatus(flag);
  }
}

export const featureFlagManager = new FeatureFlagManager();
