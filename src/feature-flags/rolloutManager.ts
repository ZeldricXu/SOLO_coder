import { RolloutStrategy, FeatureFlag, FlagEvaluationContext } from './types';
import { createHash } from 'crypto';

export class RolloutManager {
  calculateCurrentPercentage(strategy: RolloutStrategy): number {
    const now = Date.now();

    if (strategy.type === 'immediate') {
      return strategy.percentage;
    }

    if (strategy.type === 'gradual') {
      const startTime = strategy.startTime ? new Date(strategy.startTime).getTime() : now;
      const endTime = startTime + strategy.durationMs;

      if (now < startTime) return strategy.startPercentage;
      if (now >= endTime) return strategy.targetPercentage;

      const progress = (now - startTime) / strategy.durationMs;
      const currentPercentage = strategy.startPercentage + (strategy.targetPercentage - strategy.startPercentage) * progress;

      return Math.round(currentPercentage * 100) / 100;
    }

    if (strategy.type === 'scheduled') {
      if (strategy.startTime && now < new Date(strategy.startTime).getTime()) {
        return 0;
      }
      if (strategy.endTime && now >= new Date(strategy.endTime).getTime()) {
        return 0;
      }
      return strategy.percentage;
    }

    return strategy.percentage;
  }

  shouldRolloutToUser(
    flag: FeatureFlag,
    context: FlagEvaluationContext,
    currentPercentage: number
  ): boolean {
    if (currentPercentage >= 100) return true;
    if (currentPercentage <= 0) return false;

    const identifier = context.userId || context.sessionId || JSON.stringify(context.attributes);
    const hashInput = `${flag.key}:${identifier}`;
    const hash = createHash('sha256').update(hashInput).digest('hex');
    const hashValue = parseInt(hash.substring(0, 8), 16);
    const bucket = (hashValue % 10000) / 100;

    return bucket < currentPercentage;
  }

  getRolloutStatus(flag: FeatureFlag): {
    currentPercentage: number;
    isActive: boolean;
    progress: number;
  } {
    if (!flag.rollout) {
      return {
        currentPercentage: flag.enabled ? 100 : 0,
        isActive: flag.enabled,
        progress: flag.enabled ? 1 : 0,
      };
    }

    const currentPercentage = this.calculateCurrentPercentage(flag.rollout);
    const now = Date.now();
    const startTime = flag.rollout.startTime ? new Date(flag.rollout.startTime).getTime() : now;
    const endTime = startTime + flag.rollout.durationMs;

    let progress = 1;
    if (flag.rollout.type === 'gradual') {
      progress = Math.min(1, Math.max(0, (now - startTime) / flag.rollout.durationMs));
    }

    return {
      currentPercentage,
      isActive: currentPercentage > 0,
      progress,
    };
  }
}

export const rolloutManager = new RolloutManager();
