import { AnalysisRule, Language } from './types';
import { generateId, logger } from '../utils/common';

export class RuleManager {
  private rules: Map<string, AnalysisRule> = new Map();
  private languageRules: Map<Language, AnalysisRule[]> = new Map();

  constructor() {
    this.registerDefaultRules();
  }

  registerRule(rule: Omit<AnalysisRule, 'ruleId'>): AnalysisRule {
    const ruleId = generateId('rule_');
    const fullRule: AnalysisRule = { ...rule, ruleId } as AnalysisRule;

    this.rules.set(ruleId, fullRule);

    const existing = this.languageRules.get(rule.language) || [];
    existing.push(fullRule);
    this.languageRules.set(rule.language, existing);

    logger.debug(`Analysis rule registered`, { ruleId, name: rule.name, language: rule.language });
    return fullRule;
  }

  getRule(ruleId: string): AnalysisRule | undefined {
    return this.rules.get(ruleId);
  }

  getRulesByLanguage(language: Language, enabledOnly: boolean = true): AnalysisRule[] {
    let rules = this.languageRules.get(language) || [];
    if (enabledOnly) {
      rules = rules.filter(r => r.enabled);
    }
    return rules;
  }

  updateRule(ruleId: string, updates: Partial<AnalysisRule>): AnalysisRule | undefined {
    const rule = this.rules.get(ruleId);
    if (!rule) return undefined;

    const oldLanguage = rule.language;
    const updated: AnalysisRule = { ...rule, ...updates };

    if (updates.language && updates.language !== oldLanguage) {
      const oldList = this.languageRules.get(oldLanguage) || [];
      const newList = this.languageRules.get(updates.language) || [];
      const filtered = oldList.filter(r => r.ruleId !== ruleId);
      this.languageRules.set(oldLanguage, filtered);
      newList.push(updated);
      this.languageRules.set(updates.language, newList);
    } else {
      const list = this.languageRules.get(oldLanguage) || [];
      const index = list.findIndex(r => r.ruleId === ruleId);
      if (index !== -1) {
        list[index] = updated;
      }
    }

    this.rules.set(ruleId, updated);
    logger.info(`Analysis rule updated`, { ruleId });
    return updated;
  }

  deleteRule(ruleId: string): boolean {
    const rule = this.rules.get(ruleId);
    if (!rule) return false;

    const list = this.languageRules.get(rule.language) || [];
    const filtered = list.filter(r => r.ruleId !== ruleId);
    this.languageRules.set(rule.language, filtered);

    return this.rules.delete(ruleId);
  }

  listRules(language?: Language, enabledOnly: boolean = true): AnalysisRule[] {
    if (language) {
      return this.getRulesByLanguage(language, enabledOnly);
    }
    let rules = Array.from(this.rules.values());
    if (enabledOnly) {
      rules = rules.filter(r => r.enabled);
    }
    return rules;
  }

  enableRule(ruleId: string): boolean {
    return this.updateRule(ruleId, { enabled: true }) !== undefined;
  }

  disableRule(ruleId: string): boolean {
    return this.updateRule(ruleId, { enabled: false }) !== undefined;
  }

  private registerDefaultRules(): void {
    const defaultRules: Omit<AnalysisRule, 'ruleId'>[] = [
      {
        name: 'no-unused-vars',
        description: 'Detect unused variables and imports',
        language: 'typescript',
        severity: 'MINOR',
        category: 'code_smell',
        parameters: {},
        enabled: true,
        remediationEffortMinutes: 2,
      },
      {
        name: 'no-any',
        description: 'Disallow usage of any type',
        language: 'typescript',
        severity: 'MAJOR',
        category: 'code_smell',
        parameters: {},
        enabled: true,
        remediationEffortMinutes: 10,
      },
      {
        name: 'cyclomatic-complexity',
        description: 'Detect functions with high cyclomatic complexity',
        language: 'typescript',
        severity: 'MAJOR',
        category: 'code_smell',
        parameters: { maxComplexity: 10 },
        enabled: true,
        remediationEffortMinutes: 30,
      },
      {
        name: 'no-console',
        description: 'Disallow console.log statements in production code',
        language: 'javascript',
        severity: 'MINOR',
        category: 'code_smell',
        parameters: {},
        enabled: true,
        remediationEffortMinutes: 1,
      },
      {
        name: 'sql-injection',
        description: 'Detect potential SQL injection vulnerabilities',
        language: 'typescript',
        severity: 'CRITICAL',
        category: 'vulnerability',
        parameters: {},
        enabled: true,
        remediationEffortMinutes: 120,
      },
      {
        name: 'hardcoded-secrets',
        description: 'Detect hardcoded passwords and API keys',
        language: 'typescript',
        severity: 'BLOCKER',
        category: 'security_hotspot',
        parameters: {},
        enabled: true,
        remediationEffortMinutes: 60,
      },
      {
        name: 'duplicate-code',
        description: 'Detect duplicated code blocks',
        language: 'typescript',
        severity: 'MAJOR',
        category: 'duplication',
        parameters: { minLines: 10 },
        enabled: true,
        remediationEffortMinutes: 45,
      },
      {
        name: 'missing-error-handling',
        description: 'Detect missing try-catch blocks for async operations',
        language: 'typescript',
        severity: 'MAJOR',
        category: 'bug',
        parameters: {},
        enabled: true,
        remediationEffortMinutes: 20,
      },
      {
        name: 'naming-convention',
        description: 'Enforce naming conventions for variables and functions',
        language: 'typescript',
        severity: 'MINOR',
        category: 'code_smell',
        parameters: {},
        enabled: true,
        remediationEffortMinutes: 5,
      },
      {
        name: 'max-line-length',
        description: 'Enforce maximum line length',
        language: 'typescript',
        severity: 'INFO',
        category: 'code_smell',
        parameters: { maxLength: 120 },
        enabled: true,
        remediationEffortMinutes: 1,
      },
    ];

    for (const rule of defaultRules) {
      this.registerRule(rule);
    }

    logger.info(`Default analysis rules registered`, { count: defaultRules.length });
  }
}

export const ruleManager = new RuleManager();
