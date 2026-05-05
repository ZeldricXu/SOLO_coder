import { ValidationRule } from './ValidationRule';
import RequiredRule from './RequiredRule';
import MinLengthRule from './MinLengthRule';
import MaxLengthRule from './MaxLengthRule';
import PatternRule from './PatternRule';
import MinRule from './MinRule';
import MaxRule from './MaxRule';
import EmailRule from './EmailRule';
import PhoneRule from './PhoneRule';
import IdCardRule from './IdCardRule';
import CustomRule from './CustomRule';

const BUILTIN_RULES = [
  RequiredRule,
  MinLengthRule,
  MaxLengthRule,
  PatternRule,
  MinRule,
  MaxRule,
  EmailRule,
  PhoneRule,
  IdCardRule,
  CustomRule,
];

let _globalRegistry = null;

export class RuleRegistry {
  constructor() {
    this._ruleClasses = new Map();
    this._ruleInstanceCache = new Map();
  }

  register(RuleClass) {
    if (!RuleClass || !(RuleClass.prototype instanceof ValidationRule)) {
      throw new Error('Rule class must extend ValidationRule');
    }

    const type = RuleClass.type;
    if (!type) {
      throw new Error('Rule class must have a static type property');
    }

    this._ruleClasses.set(type, RuleClass);
    this._ruleInstanceCache.delete(type);
  }

  unregister(type) {
    this._ruleClasses.delete(type);
    this._ruleInstanceCache.delete(type);
  }

  getRuleClass(type) {
    return this._ruleClasses.get(type);
  }

  hasRule(type) {
    return this._ruleClasses.has(type);
  }

  getRegisteredTypes() {
    return Array.from(this._ruleClasses.keys());
  }

  getRuleDescriptions() {
    const descriptions = [];
    this._ruleClasses.forEach((RuleClass, type) => {
      descriptions.push({
        type,
        priority: RuleClass.priority,
        description: RuleClass.description,
      });
    });
    return descriptions;
  }

  createRule(type, config = {}) {
    const RuleClass = this._ruleClasses.get(type);
    if (!RuleClass) {
      console.warn(`Rule type "${type}" not registered`);
      return null;
    }
    return new RuleClass(config);
  }

  createRulesFromComponent(component) {
    const rules = [];

    if (!component) {
      return rules;
    }

    this._ruleClasses.forEach((RuleClass) => {
      const rule = RuleClass.detectRuleFromComponent(component);
      if (rule) {
        rules.push(rule);
      }
    });

    return this.sortRulesByPriority(rules);
  }

  sortRulesByPriority(rules) {
    return [...rules].sort((a, b) => (b.priority || 0) - (a.priority || 0));
  }

  registerBuiltinRules() {
    BUILTIN_RULES.forEach((RuleClass) => {
      this.register(RuleClass);
    });
  }

  fromJSON(rulesJSON) {
    if (!Array.isArray(rulesJSON)) {
      return [];
    }

    return rulesJSON
      .map((ruleJSON) => {
        if (!ruleJSON || !ruleJSON.type) {
          return null;
        }
        return this.createRule(ruleJSON.type, ruleJSON.config || {});
      })
      .filter(Boolean);
  }

  toJSON(rules) {
    if (!Array.isArray(rules)) {
      return [];
    }

    return rules.map((rule) => rule.toJSON());
  }
}

export function createRuleRegistry() {
  const registry = new RuleRegistry();
  registry.registerBuiltinRules();
  return registry;
}

export function getGlobalRegistry() {
  if (!_globalRegistry) {
    _globalRegistry = createRuleRegistry();
  }
  return _globalRegistry;
}

export function setGlobalRegistry(registry) {
  _globalRegistry = registry;
}

export default RuleRegistry;
