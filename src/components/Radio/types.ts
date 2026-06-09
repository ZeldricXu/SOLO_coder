import type { ComponentBaseProps } from '@types';

export interface RadioOption {
  value: string;
  label: React.ReactNode;
  disabled?: boolean;
}

export interface RadioProps extends ComponentBaseProps, Omit<React.InputHTMLAttributes<HTMLInputElement>> {
  label?: React.ReactNode;
  value: string;
  error?: string | boolean;
}

export interface RadioGroupProps extends ComponentBaseProps {
  value?: string;
  defaultValue?: string;
  onChange?: (value: string) => void;
  options?: RadioOption[];
  disabled?: boolean;
  children?: React.ReactNode;
  name: string;
  orientation?: 'horizontal' | 'vertical';
  label?: string;
}
