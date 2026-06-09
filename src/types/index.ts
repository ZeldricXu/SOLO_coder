export type ComponentSize = 'sm' | 'md' | 'lg';

export type ThemeMode = 'light' | 'dark';

export interface ColorTokens {
  primary: string;
  primaryHover: string;
  primaryActive: string;
  primaryDisabled: string;
  secondary: string;
  secondaryHover: string;
  secondaryActive: string;
  secondaryDisabled: string;
  danger: string;
  dangerHover: string;
  dangerActive: string;
  dangerDisabled: string;
  success: string;
  successHover: string;
  successActive: string;
  warning: string;
  warningHover: string;
  warningActive: string;
  info: string;
  infoHover: string;
  infoActive: string;
  background: string;
  backgroundSecondary: string;
  backgroundTertiary: string;
  surface: string;
  surfaceHover: string;
  surfaceActive: string;
  border: string;
  borderStrong: string;
  textPrimary: string;
  textSecondary: string;
  textTertiary: string;
  textDisabled: string;
  textInverse: string;
  error: string;
  errorBg: string;
  focusRing: string;
  overlay: string;
}

export interface SpacingTokens {
  xs: string;
  sm: string;
  md: string;
  lg: string;
  xl: string;
  '2xl': string;
  '3xl': string;
  '4xl': string;
}

export interface RadiusTokens {
  none: string;
  sm: string;
  md: string;
  lg: string;
  xl: string;
  full: string;
}

export interface ShadowTokens {
  sm: string;
  md: string;
  lg: string;
  xl: string;
  '2xl': string;
  inner: string;
  focus: string;
}

export interface TypographyTokens {
  fontFamily: string;
  fontSize: {
    xs: string;
    sm: string;
    md: string;
    lg: string;
    xl: string;
    '2xl': string;
    '3xl': string;
  };
  fontWeight: {
    normal: string;
    medium: string;
    semibold: string;
    bold: string;
  };
  lineHeight: {
    tight: string;
    normal: string;
    relaxed: string;
  };
}

export interface DesignTokens {
  colors: ColorTokens;
  spacing: SpacingTokens;
  radius: RadiusTokens;
  shadows: ShadowTokens;
  typography: TypographyTokens;
}

export interface ThemeConfig {
  mode: ThemeMode;
  tokens: DesignTokens;
}

export interface ComponentBaseProps {
  size?: ComponentSize;
  disabled?: boolean;
  className?: string;
}
