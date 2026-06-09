import type { ComponentBaseProps } from '@types';

export type InputType = 'text' | 'password' | 'email' | 'number' | 'tel' | 'url' | 'search';

export interface InputProps extends ComponentBaseProps, Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size'> {
  type?: InputType;
  placeholder?: string;
  error?: string | boolean;
  required?: boolean;
  prefix?: React.ReactNode;
  suffix?: React.ReactNode;
  label?: string;
  helperText?: string;
  showCount?: boolean;
  maxLength?: number;
}
