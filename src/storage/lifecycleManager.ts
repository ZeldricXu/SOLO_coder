import { LifecycleRule, StoredFile } from './types';
import { StorageBackend } from './backends';
import { generateId, logger } from '../utils/common';

export class LifecycleManager {
  private rules: Map<string, LifecycleRule> = new Map();
  private backends: Map<string, StorageBackend> = new Map();

  addRule(rule: Omit<LifecycleRule, 'id'>): LifecycleRule {
    const fullRule: LifecycleRule = {
      ...rule,
      id: generateId('lcr_'),
    };

    this.rules.set(fullRule.id, fullRule);
    logger.info(`Lifecycle rule added`, { id: fullRule.id, storageId: fullRule.storageId });
    return fullRule;
  }

  registerBackend(storageId: string, backend: StorageBackend): void {
    this.backends.set(storageId, backend);
  }

  async evaluateRules(): Promise<{ rulesEvaluated: number; filesProcessed: number }> {
    let filesProcessed = 0;
    const activeRules = Array.from(this.rules.values()).filter(r => r.enabled);

    for (const rule of activeRules) {
      const backend = this.backends.get(rule.storageId);
      if (!backend) continue;

      const result = await backend.list({ includeArchived: true, limit: 1000 });
      for (const file of result.files) {
        if (this.fileMatchesRule(file, rule)) {
          await this.applyRule(file, rule, backend);
          filesProcessed++;
        }
      }
    }

    logger.info(`Lifecycle rules evaluated`, {
      rulesEvaluated: activeRules.length,
      filesProcessed,
    });

    return { rulesEvaluated: activeRules.length, filesProcessed };
  }

  private fileMatchesRule(file: StoredFile, rule: LifecycleRule): boolean {
    const { condition } = rule;

    if (condition.prefix && !file.name.startsWith(condition.prefix)) {
      return false;
    }

    if (condition.tags?.length && !condition.tags.some(tag => file.tags.includes(tag))) {
      return false;
    }

    if (condition.ageDays) {
      const ageMs = Date.now() - new Date(file.createdAt).getTime();
      const ageDays = ageMs / (1000 * 60 * 60 * 24);
      if (ageDays < condition.ageDays) {
        return false;
      }
    }

    if (condition.sizeGreaterThan && file.sizeBytes < condition.sizeGreaterThan) {
      return false;
    }

    return true;
  }

  private async applyRule(file: StoredFile, rule: LifecycleRule, backend: StorageBackend): Promise<void> {
    switch (rule.action.type) {
      case 'archive':
        file.archived = true;
        logger.debug(`File archived`, { fileId: file.fileId, ruleId: rule.id });
        break;
      case 'delete':
        await backend.delete(file.fileId);
        logger.debug(`File deleted by lifecycle`, { fileId: file.fileId, ruleId: rule.id });
        break;
    }
  }

  getRule(id: string): LifecycleRule | undefined {
    return this.rules.get(id);
  }

  listRules(storageId?: string): LifecycleRule[] {
    let rules = Array.from(this.rules.values());
    if (storageId) {
      rules = rules.filter(r => r.storageId === storageId);
    }
    return rules;
  }

  updateRule(id: string, updates: Partial<LifecycleRule>): LifecycleRule | undefined {
    const rule = this.rules.get(id);
    if (!rule) return undefined;

    const updated: LifecycleRule = { ...rule, ...updates };
    this.rules.set(id, updated);
    logger.info(`Lifecycle rule updated`, { id });
    return updated;
  }

  deleteRule(id: string): boolean {
    return this.rules.delete(id);
  }
}

export const lifecycleManager = new LifecycleManager();
