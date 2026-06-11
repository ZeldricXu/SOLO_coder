import type { ComponentBaseProps } from '@types';

export interface TagProps extends ComponentBaseProps {
  color?: 'default' | 'primary' | 'success' | 'warning' | 'error';
  size?: 'sm' | 'md' | 'lg';
  closable?: boolean;
  onClose?: (e: React.MouseEvent) => void;
  disabled?: boolean;
  children: React.ReactNode;
  className?: string;
}
