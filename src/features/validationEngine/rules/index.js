import ValidationRule from './ValidationRule';
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
import RuleRegistry, {
  getGlobalRegistry,
  setGlobalRegistry,
  createRuleRegistry,
} from './RuleRegistry';
import {
  VALIDATION_TYPE,
  VALIDATION_MESSAGES,
  PATTERNS,
} from './constants';

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

export {
  ValidationRule,
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
  VALIDATION_TYPE,
  VALIDATION_MESSAGES,
  PATTERNS,
  BUILTIN_RULES,
};

export default ValidationRule;
