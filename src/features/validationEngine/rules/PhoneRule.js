import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES, PATTERNS } from './constants';

export class PhoneRule extends ValidationRule {
  static type = VALIDATION_TYPE.PHONE;

  static priority = 29;

  static description = '手机号格式校验：检查值是否为有效的手机号';

  getDefaultConfig() {
    return {
      pattern: PATTERNS[VALIDATION_TYPE.PHONE],
    };
  }

  shouldValidate(value) {
    return !this.isEmpty(value);
  }

  execute(value, component) {
    const strValue = String(value);
    const pattern = this.config.pattern;

    if (!pattern.test(strValue)) {
      return false;
    }
    return true;
  }

  getMessage(component) {
    return VALIDATION_MESSAGES[VALIDATION_TYPE.PHONE](component?.label);
  }

  static shouldApplyToComponent(component) {
    return (
      component &&
      component.format_type &&
      (component.format_type === VALIDATION_TYPE.PHONE ||
        (Array.isArray(component.format_type) &&
          component.format_type.includes(VALIDATION_TYPE.PHONE)))
    );
  }

  static detectRuleFromComponent(component) {
    if (this.shouldApplyToComponent(component)) {
      return new this();
    }
    return null;
  }
}

export default PhoneRule;
