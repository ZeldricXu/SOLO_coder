import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES, PATTERNS } from './constants';

export class IdCardRule extends ValidationRule {
  static type = VALIDATION_TYPE.ID_CARD;

  static priority = 28;

  static description = '身份证格式校验：检查值是否为有效的身份证号';

  getDefaultConfig() {
    return {
      pattern: PATTERNS[VALIDATION_TYPE.ID_CARD],
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
    return VALIDATION_MESSAGES[VALIDATION_TYPE.ID_CARD](component?.label);
  }

  static shouldApplyToComponent(component) {
    return (
      component &&
      component.format_type &&
      (component.format_type === VALIDATION_TYPE.ID_CARD ||
        (Array.isArray(component.format_type) &&
          component.format_type.includes(VALIDATION_TYPE.ID_CARD)))
    );
  }

  static detectRuleFromComponent(component) {
    if (this.shouldApplyToComponent(component)) {
      return new this();
    }
    return null;
  }
}

export default IdCardRule;
