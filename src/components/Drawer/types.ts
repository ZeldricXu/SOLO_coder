export type DrawerPlacement = 'left' | 'right' | 'top' | 'bottom';

export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  title?: React.ReactNode;
  children: React.ReactNode;
  placement?: DrawerPlacement;
  width?: string | number;
  height?: string | number;
  closable?: boolean;
  maskClosable?: boolean;
  destroyOnClose?: boolean;
  className?: string;
  maskClassName?: string;
}
