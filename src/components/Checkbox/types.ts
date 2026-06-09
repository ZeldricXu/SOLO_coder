import type { ComponentBaseProps } from '@types';

export interface CheckboxProps extends ComponentBaseProps, Omit<React.InputHTMLAttributes<HTMLInputElement>> {
  label?: React.ReactNode;
  indeterminate?: boolean;
  error?: string | boolean;
}

export interface CheckboxGroupProps extends ComponentBaseProps {
  value?: string[];
  defaultValue?: string[];
  onChange?: (value: string[]) => void;
  disabled?: boolean;
  children: React.ReactNode;
  name?: string;
}
