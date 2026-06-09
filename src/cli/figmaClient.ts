import type { FigmaAPIResponse, FigmaVariable, DesignToken, TokenCollection, ThemeTokens } from './types';

const FIGMA_API_BASE = 'https://api.figma.com/v1';

export class FigmaClient {
  private apiKey: string;
  private fileId: string;

  constructor(apiKey: string, fileId: string) {
    this.apiKey = apiKey;
    this.fileId = fileId;
  }

  async fetchVariables(): Promise<FigmaAPIResponse> {
    const response = await fetch(
      `${FIGMA_API_BASE}/files/${this.fileId}/variables/local`,
      {
        headers: {
          'X-Figma-Token': this.apiKey,
          'Content-Type': 'application/json',
        },
      },
    );

    if (!response.ok) {
      throw new Error(`Figma API error: ${response.status} ${response.statusText}`);
    }

    return response.json();
  }
}

export const rgbaToHex = (r: number, g: number, b: number, a: number = 1): string => {
  const toHex = (n: number): string => {
    const hex = Math.round(n * 255).toString(16).padStart(2, '0');
    return hex;
  };

  const hex = `#${toHex(r)}${toHex(g)}${toHex(b)}`;
  return a < 1 ? `${hex}${toHex(a)}` : hex;
};

export const parseFigmaValue = (
  variable: FigmaVariable,
  modeId: string,
): string | number => {
  const value = variable.valuesByMode[modeId];
  if (!value) return '';

  if (value.type === 'COLOR' && typeof value.value === 'object' && 'r' in value.value) {
    return rgbaToHex(value.value.r, value.value.g, value.value.b, value.value.a);
  }

  if (value.type === 'FLOAT' || value.type === 'STRING' || value.type === 'BOOLEAN') {
    return value.value as string | number;
  }

  return String(value.value);
};

export const categorizeToken = (name: string): DesignToken['type'] => {
  const lowerName = name.toLowerCase();
  if (lowerName.includes('color') || lowerName.includes('bg') || lowerName.includes('text')) return 'color';
  if (lowerName.includes('spacing') || lowerName.includes('padding') || lowerName.includes('margin')) return 'spacing';
  if (lowerName.includes('radius') || lowerName.includes('rounded')) return 'radius';
  if (lowerName.includes('shadow')) return 'shadow';
  if (lowerName.includes('font') || lowerName.includes('text')) return 'font';
  return 'color';
};

export const transformToTokens = (
  response: FigmaAPIResponse,
  lightModeName: string = 'Light',
  darkModeName: string = 'Dark',
): ThemeTokens => {
  const { variables, variableCollections } = response.meta;
  const tokens: ThemeTokens = { light: {}, dark: {} };

  Object.values(variableCollections).forEach((collection) => {
    const lightMode = collection.modes.find((m) => m.name.toLowerCase() === lightModeName.toLowerCase());
    const darkMode = collection.modes.find((m) => m.name.toLowerCase() === darkModeName.toLowerCase());

    collection.variableIds.forEach((varId) => {
      const variable = variables[varId];
      if (!variable) return;

      const nameParts = variable.name.split('/');
      const tokenName = nameParts.pop() || variable.name;
      const category = categorizeToken(variable.name);

      const lightValue = lightMode ? parseFigmaValue(variable, lightMode.modeId) : '';
      const darkValue = darkMode ? parseFigmaValue(variable, darkMode.modeId) : lightValue;

      const lightToken: DesignToken = {
        name: tokenName,
        value: lightValue,
        type: category,
        description: variable.key,
      };

      const darkToken: DesignToken = {
        name: tokenName,
        value: darkValue,
        type: category,
        description: variable.key,
      };

      let lightTarget: TokenCollection = tokens.light;
      let darkTarget: TokenCollection = tokens.dark;

      nameParts.forEach((part) => {
        if (!lightTarget[part]) lightTarget[part] = {};
        if (!darkTarget[part]) darkTarget[part] = {};
        lightTarget = lightTarget[part] as TokenCollection;
        darkTarget = darkTarget[part] as TokenCollection;
      });

      lightTarget[tokenName] = lightToken;
      darkTarget[tokenName] = darkToken;
    });
  });

  return tokens;
};
