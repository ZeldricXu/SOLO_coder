import type { ComponentBaseProps } from '@types';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
  icon?: React.ReactNode;
}

export interface SelectProps extends ComponentBaseProps {
  options: SelectOption[];
  size?: 'sm' | 'md' | 'lg';
  value?: string;
  defaultValue?: string;
  placeholder?: string;
  onChange?: (value: string, option: SelectOption | null) => void;
  error?: string | boolean;
  required?: boolean;
  label?: string;
  helperText?: string;
  disabled?: boolean;
  clearable?: boolean;
  className?: string;
}
