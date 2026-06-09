import type { ComponentBaseProps } from '@types';

export type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger';

export type ButtonType = 'button' | 'submit' | 'reset';

export interface ButtonProps extends ComponentBaseProps, React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  type?: ButtonType;
  loading?: boolean;
  fullWidth?: boolean;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
  children: React.ReactNode;
}
