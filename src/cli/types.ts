export interface FigmaVariable {
  id: string;
  name: string;
  key: string;
  variableCollectionId: string;
  resolvedType: 'COLOR' | 'FLOAT' | 'STRING' | 'BOOLEAN';
  valuesByMode: Record<string, FigmaVariableValue>;
}

export interface FigmaVariableValue {
  type: 'VARIABLE_ALIAS' | 'COLOR' | 'FLOAT' | 'STRING' | 'BOOLEAN';
  value: string | number | boolean | { r: number; g: number; b: number; a: number };
}

export interface FigmaVariableCollection {
  id: string;
  name: string;
  modes: Array<{ modeId: string; name: string }>;
  variableIds: string[];
}

export interface FigmaAPIResponse {
  meta: {
    variableCollections: Record<string, FigmaVariableCollection>;
    variables: Record<string, FigmaVariable>;
  };
  status: number;
  error?: boolean;
}

export interface DesignToken {
  name: string;
  value: string | number;
  type: 'color' | 'spacing' | 'radius' | 'shadow' | 'font';
  description?: string;
}

export interface TokenCollection {
  [key: string]: DesignToken | TokenCollection;
}

export interface ThemeTokens {
  light: TokenCollection;
  dark: TokenCollection;
}
