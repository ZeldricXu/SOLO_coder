import type { ComponentBaseProps } from '@types';

export interface TabItem {
  key: string;
  label: React.ReactNode;
  disabled?: boolean;
}

export interface TabsProps extends ComponentBaseProps {
  items: TabItem[];
  activeKey?: string;
  defaultActiveKey?: string;
  onChange?: (activeKey: string) => void;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'line' | 'card' | 'capsule';
  placement?: 'top' | 'bottom' | 'left' | 'right';
  disabled?: boolean;
  children?: React.ReactNode;
}

export interface TabPaneProps extends ComponentBaseProps {
  tabKey: string;
  children?: React.ReactNode;
}
