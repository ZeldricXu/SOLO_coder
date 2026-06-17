import { clamp } from './math';

export interface FormulaContext {
  [key: string]: number | undefined;
}

export function evaluateFormula(formula: string, context: FormulaContext): number {
  const tokens = tokenize(formula);
  const postfix = toPostfix(tokens);
  return evaluatePostfix(postfix, context);
}

function tokenize(formula: string): string[] {
  const tokens: string[] = [];
  let current = '';
  
  for (let i = 0; i < formula.length; i++) {
    const char = formula[i];
    
    if (/\s/.test(char)) {
      if (current) {
        tokens.push(current);
        current = '';
      }
      continue;
    }
    
    if (/[+\-*/()^%]/.test(char)) {
      if (current) {
        tokens.push(current);
        current = '';
      }
      if (char === '-' && (tokens.length === 0 || /[+\-*/(^]/.test(tokens[tokens.length - 1]))) {
        current = char;
      } else {
        tokens.push(char);
      }
    } else if (/[a-zA-Z0-9_.]/.test(char)) {
      current += char;
    } else {
      throw new Error(`Invalid character in formula: ${char}`);
    }
  }
  
  if (current) {
    tokens.push(current);
  }
  
  return tokens;
}

function toPostfix(tokens: string[]): string[] {
  const output: string[] = [];
  const operators: string[] = [];
  const precedence: Record<string, number> = {
    '+': 1,
    '-': 1,
    '*': 2,
    '/': 2,
    '%': 2,
    '^': 3
  };
  
  for (const token of tokens) {
    if (/^-?\d+(\.\d+)?$/.test(token)) {
      output.push(token);
    } else if (/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(token)) {
      output.push(token);
    } else if (token === '(') {
      operators.push(token);
    } else if (token === ')') {
      while (operators.length > 0 && operators[operators.length - 1] !== '(') {
        output.push(operators.pop()!);
      }
      operators.pop();
    } else if (precedence[token] !== undefined) {
      while (
        operators.length > 0 &&
        operators[operators.length - 1] !== '(' &&
        precedence[operators[operators.length - 1]] >= precedence[token]
      ) {
        output.push(operators.pop()!);
      }
      operators.push(token);
    } else {
      throw new Error(`Unknown token: ${token}`);
    }
  }
  
  while (operators.length > 0) {
    output.push(operators.pop()!);
  }
  
  return output;
}

function evaluatePostfix(postfix: string[], context: FormulaContext): number {
  const stack: number[] = [];
  
  for (const token of postfix) {
    if (/^-?\d+(\.\d+)?$/.test(token)) {
      stack.push(parseFloat(token));
    } else if (/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(token)) {
      const value = context[token];
      if (value === undefined) {
        throw new Error(`Undefined variable in formula: ${token}`);
      }
      stack.push(value);
    } else {
      const b = stack.pop()!;
      const a = stack.pop()!;
      
      switch (token) {
        case '+': stack.push(a + b); break;
        case '-': stack.push(a - b); break;
        case '*': stack.push(a * b); break;
        case '/': stack.push(a / b); break;
        case '%': stack.push(a % b); break;
        case '^': stack.push(Math.pow(a, b)); break;
        default: throw new Error(`Unknown operator: ${token}`);
      }
    }
  }
  
  return stack[0];
}

export interface DamageFormulaConfig {
  baseFormula: string;
  minDamage: number;
  maxDamage: number;
  critMultiplier: number;
}

export function calculateDamage(
  config: DamageFormulaConfig,
  baseAttack: number,
  targetDefense: number,
  armorPenetration: number = 0,
  isCrit: boolean = false,
  modifiers: Array<{ type: 'multiply' | 'add'; value: number }> = []
): number {
  const effectiveDefense = Math.max(0, targetDefense - armorPenetration);
  
  let damage = evaluateFormula(config.baseFormula, {
    attack: baseAttack,
    defense: effectiveDefense,
    baseAttack,
    targetDefense,
    armorPenetration
  });
  
  for (const modifier of modifiers) {
    if (modifier.type === 'add') {
      damage += modifier.value;
    } else {
      damage *= modifier.value;
    }
  }
  
  if (isCrit) {
    damage *= config.critMultiplier;
  }
  
  return clamp(damage, config.minDamage, config.maxDamage);
}

export function calculateHitChance(
  baseAccuracy: number,
  targetEvasion: number,
  modifiers: Array<{ type: 'multiply' | 'add'; value: number }> = []
): number {
  let hitChance = baseAccuracy - targetEvasion;
  
  for (const modifier of modifiers) {
    if (modifier.type === 'add') {
      hitChance += modifier.value;
    } else {
      hitChance *= modifier.value;
    }
  }
  
  return clamp(hitChance, 0, 100);
}

export function calculateCritChance(
  baseCritRate: number,
  targetCritResistance: number = 0,
  modifiers: Array<{ type: 'multiply' | 'add'; value: number }> = []
): number {
  let critChance = baseCritRate - targetCritResistance;
  
  for (const modifier of modifiers) {
    if (modifier.type === 'add') {
      critChance += modifier.value;
    } else {
      critChance *= modifier.value;
    }
  }
  
  return clamp(critChance, 0, 100);
}

export function calculateElementMultiplier(
  attackerElement: string,
  defenderElement: string,
  elementChart: Record<string, { strong: string[]; weak: string[] }>,
  advantageMultiplier: number = 1.5,
  disadvantageMultiplier: number = 0.75
): number {
  const attackerData = elementChart[attackerElement];
  if (!attackerData) return 1;
  
  if (attackerData.strong.includes(defenderElement)) {
    return advantageMultiplier;
  }
  
  if (attackerData.weak.includes(defenderElement)) {
    return disadvantageMultiplier;
  }
  
  return 1;
}
