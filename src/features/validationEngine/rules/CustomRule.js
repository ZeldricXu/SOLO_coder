import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES } from './constants';

export class CustomRule extends ValidationRule {
  static type = VALIDATION_TYPE.CUSTOM;

  static priority = 10;

  static description = '自定义校验：执行用户定义的校验函数';

  getDefaultConfig() {
    return {
      validator: null,
      message: '',
    };
  }

  shouldValidate(value) {
    return !!this.config.validator;
  }

  execute(value, component) {
    const validator = this.config.validator;

    if (typeof validator === 'function') {
      try {
        const result = validator(value, component);
        return result === true;
      } catch (e) {
        console.warn('Custom validator error:', e);
        return true;
      }
    }

    if (typeof validator === 'string') {
      try {
        const fn = new Function('value', 'component', validator);
        const result = fn(value, component);
        return result === true;
      } catch (e) {
        console.warn('Custom validator string error:', e);
        return true;
      }
    }

    return true;
  }

  getMessage(component) {
    return this.config.message || VALIDATION_MESSAGES[VALIDATION_TYPE.CUSTOM](component?.label);
  }

  static shouldApplyToComponent(component) {
    return (
      component &&
      component.validation &&
      component.validation.validator
    );
  }

  static detectRuleFromComponent(component) {
    if (
      component &&
      component.validation &&
      component.validation.validator
    ) {
      return new this({
        validator: component.validation.validator,
        message: component.validation.message,
      });
    }
    return null;
  }

  toJSON() {
    const json = super.toJSON();
    if (typeof this.config.validator === 'function') {
      json.config.validator = this.config.validator.toString();
    }
    return json;
  }
}

export default CustomRule;
