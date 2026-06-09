import type { ComponentBaseProps } from '@types';

export interface SwitchProps extends ComponentBaseProps, Omit<React.ButtonHTMLAttributes<HTMLButtonElement>> {
  checked?: boolean;
  defaultChecked?: boolean;
  onChange?: (checked: boolean) => void;
  label?: React.ReactNode;
  loading?: boolean;
}
