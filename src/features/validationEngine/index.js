import {
  ValidationRule,
  VALIDATION_TYPE,
  VALIDATION_MESSAGES,
  PATTERNS,
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
  RuleRegistry,
  getGlobalRegistry,
  setGlobalRegistry,
  createRuleRegistry,
  BUILTIN_RULES,
} from './rules';

class ValidationEngine {
  constructor(registry = null) {
    this._registry = registry || getGlobalRegistry();
  }

  get registry() {
    return this._registry;
  }

  set registry(registry) {
    if (registry instanceof RuleRegistry) {
      this._registry = registry;
    }
  }

  registerRule(ruleClass) {
    this._registry.register(ruleClass);
    return this;
  }

  registerRules(ruleClasses) {
    ruleClasses.forEach((ruleClass) => this.registerRule(ruleClass));
    return this;
  }

  createRule(type, config = {}) {
    return this._registry.createRule(type, config);
  }

  createRulesFromComponent(component) {
    const rules = this._registry.createRulesFromComponent(component);
    return this._registry.sortRulesByPriority(rules);
  }

  isEmpty(value) {
    if (value === null || value === undefined || value === '') {
      return true;
    }
    if (Array.isArray(value) && value.length === 0) {
      return true;
    }
    if (typeof value === 'object' && Object.keys(value).length === 0) {
      return true;
    }
    return false;
  }

  validateFieldWithRules(value, component, rules = []) {
    for (const rule of rules) {
      const result = rule.validate(value, component);
      if (!result.isValid) {
        return result;
      }
    }
    return {
      isValid: true,
      errors: [],
    };
  }

  validateField(value, component) {
    const rules = this.createRulesFromComponent(component);
    return this.validateFieldWithRules(value, component, rules);
  }

  validateFieldPluginBased(value, component) {
    return this.validateField(value, component);
  }

  validateForm(formData, components) {
    const results = {};
    let allValid = true;

    components.forEach((component) => {
      const componentId = component.component_id;
      const value = formData[componentId];
      const result = this.validateField(value, component);
      results[componentId] = result;
      if (!result.isValid) {
        allValid = false;
      }
    });

    return {
      isValid: allValid,
      fieldResults: results,
    };
  }

  validateFormPluginBased(formData, components) {
    return this.validateForm(formData, components);
  }

  validateFormWithSteps(formData, formConfig) {
    const allComponents = [];

    if (formConfig.form_type === 'multi_step' && formConfig.steps) {
      formConfig.steps.forEach((step) => {
        if (step.components) {
          allComponents.push(...step.components);
        }
      });
    } else if (formConfig.components) {
      allComponents.push(...formConfig.components);
    }

    return this.validateForm(formData, allComponents);
  }

  addCustomRule(type, config) {
    return new CustomRule({
      ...config,
      type,
    });
  }

  getSupportedRuleTypes() {
    return this._registry.getRegisteredTypes();
  }

  getSupportedRules() {
    return this._registry.getRuleDescriptions();
  }
}

export const validationEngine = new ValidationEngine();

export {
  ValidationRule,
  VALIDATION_TYPE,
  VALIDATION_MESSAGES,
  PATTERNS,
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
  RuleRegistry,
  getGlobalRegistry,
  setGlobalRegistry,
  createRuleRegistry,
  BUILTIN_RULES,
};

export default ValidationEngine;
