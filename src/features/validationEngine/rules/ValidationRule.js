export class ValidationRule {
  static type = 'base';

  static priority = 0;

  static description = '';

  constructor(config = {}) {
    this.config = {
      ...this.getDefaultConfig(),
      ...config,
    };
    this.type = this.constructor.type;
    this.priority = this.constructor.priority;
  }

  getDefaultConfig() {
    return {};
  }

  getMessage(component) {
    return '';
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

  shouldValidate(value) {
    return true;
  }

  validate(value, component) {
    if (!this.shouldValidate(value)) {
      return { isValid: true, errors: [] };
    }

    const isValid = this.execute(value, component);

    if (isValid) {
      return { isValid: true, errors: [] };
    }

    const message = this.getMessage(component);
    return {
      isValid: false,
      errors: [message],
    };
  }

  execute(value, component) {
    return true;
  }

  executeAsync(value, component) {
    return Promise.resolve(this.execute(value, component));
  }

  toJSON() {
    return {
      type: this.type,
      config: this.config,
    };
  }

  static fromJSON(json) {
    return new this(json.config || {});
  }

  static shouldApplyToComponent(component) {
    return true;
  }

  static detectRuleFromComponent(component) {
    if (component) {
      return null;
    }
    return null;
  }
}

export default ValidationRule;
