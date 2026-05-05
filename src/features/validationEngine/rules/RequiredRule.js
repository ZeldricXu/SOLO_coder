import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES } from './constants';

export class RequiredRule extends ValidationRule {
  static type = VALIDATION_TYPE.REQUIRED;

  static priority = 100;

  static description = '必填校验：检查值是否为空';

  getDefaultConfig() {
    return {
      required: true,
    };
  }

  shouldValidate(value) {
    return true;
  }

  execute(value, component) {
    if (this.isEmpty(value)) {
      return false;
    }
    return true;
  }

  getMessage(component) {
    return VALIDATION_MESSAGES[VALIDATION_TYPE.REQUIRED](component?.label);
  }

  static shouldApplyToComponent(component) {
    return component && component.required === true;
  }

  static detectRuleFromComponent(component) {
    if (component && component.required) {
      return new this();
    }
    return null;
  }
}

export default RequiredRule;
