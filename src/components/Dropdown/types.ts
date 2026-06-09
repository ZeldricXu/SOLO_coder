import type { ComponentBaseProps } from '@types';

export type MenuItemType = 'item' | 'divider' | 'submenu';

export interface DropdownMenuItem {
  key: string;
  label: string;
  type?: MenuItemType;
  icon?: React.ReactNode;
  shortcut?: string;
  disabled?: boolean;
  danger?: boolean;
  onClick?: () => void;
  children?: DropdownMenuItem[];
}

export interface DropdownProps extends ComponentBaseProps {
  items: DropdownMenuItem[];
  trigger?: React.ReactNode;
  triggerMode?: 'click' | 'hover';
  placement?: 'bottom-start' | 'bottom-end' | 'bottom' | 'top-start' | 'top-end' | 'top';
  disabled?: boolean;
  onOpenChange?: (open: boolean) => void;
  onSelect?: (key: string, item: DropdownMenuItem) => void;
  closeOnSelect?: boolean;
}
