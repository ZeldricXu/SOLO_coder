import type { TokenCollection, DesignToken, ThemeTokens } from './types';
import * as fs from 'fs';
import * as path from 'path';

const toCSSVarName = (name: string): string => {
  return `--${name.replace(/([A-Z])/g, '-$1').toLowerCase().replace(/_/g, '-')}`;
};

const flattenTokens = (
  collection: TokenCollection,
  prefix: string = '',
): Array<{ name: string; token: DesignToken }> => {
  const result: Array<{ name: string; token: DesignToken }> = [];

  Object.entries(collection).forEach(([key, value]) => {
    const fullName = prefix ? `${prefix}-${key}` : key;

    if ('value' in value && 'type' in value) {
      result.push({ name: fullName, token: value as DesignToken });
    } else {
      result.push(...flattenTokens(value as TokenCollection, fullName));
    }
  });

  return result;
};

export const generateCSSVariables = (tokens: ThemeTokens, outputPath: string): void => {
  const lightTokens = flattenTokens(tokens.light);
  const darkTokens = flattenTokens(tokens.dark);

  let css = `:root,
[data-theme='light'] {
`;

  lightTokens.forEach(({ name, token }) => {
    css += `  ${toCSSVarName(name)}: ${token.value};\n`;
  });

  css += `}

[data-theme='dark'] {
`;

  darkTokens.forEach(({ name, token }) => {
    css += `  ${toCSSVarName(name)}: ${token.value};\n`;
  });

  css += '}\n';

  fs.writeFileSync(path.join(outputPath, 'variables.generated.css'), css);
};

export const generateTypeScriptTypes = (tokens: ThemeTokens, outputPath: string): void => {
  const lightTokens = flattenTokens(tokens.light);

  const tokenNames = lightTokens.map(({ name }) => `'${toCSSVarName(name).slice(2)}'`).join(' | ');

  let types = `export interface ColorTokens {
  [key: string]: string;
}

export interface SpacingTokens {
  [key: string]: string;
}

export interface RadiusTokens {
  [key: string]: string;
}

export interface ShadowTokens {
  [key: string]: string;
}

export interface FontTokens {
  [key: string]: string;
}

export interface DesignTokens {
  color: ColorTokens;
  spacing: SpacingTokens;
  radius: RadiusTokens;
  shadow: ShadowTokens;
  font: FontTokens;
}

export type Theme = 'light' | 'dark';

export type CSSVariableName = ${tokenNames};
`;

  fs.writeFileSync(path.join(outputPath, 'types.generated.ts'), types);
};

export const generateTokenObjects = (tokens: ThemeTokens, outputPath: string): void => {
  const generateObject = (collection: TokenCollection): string => {
    let result = '{\n';

    Object.entries(collection).forEach(([key, value]) => {
      if ('value' in value && 'type' in value) {
        const token = value as DesignToken;
        result += `  ${key}: '${token.value}',\n`;
      } else {
        result += `  ${key}: ${generateObject(value as TokenCollection)},\n`;
      }
    });

    result += '}';
    return result;
  };

  let content = `import type { DesignTokens } from './types';

export const lightTheme: DesignTokens = ${generateObject(tokens.light)};

export const darkTheme: DesignTokens = ${generateObject(tokens.dark)};
`;

  fs.writeFileSync(path.join(outputPath, 'tokens.generated.ts'), content);
};

export const generateIndexFile = (outputPath: string): void => {
  const content = `export { lightTheme, darkTheme } from './tokens.generated';
export type { DesignTokens, ColorTokens, SpacingTokens, RadiusTokens, ShadowTokens, FontTokens, Theme, CSSVariableName } from './types.generated';
export * from './ThemeContext';
`;

  fs.writeFileSync(path.join(outputPath, 'index.generated.ts'), content);
};
