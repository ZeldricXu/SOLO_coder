export interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  width?: string | number;
  centered?: boolean;
  maskClosable?: boolean;
  closable?: boolean;
  escapable?: boolean;
  destroyOnClose?: boolean;
  className?: string;
  maskClassName?: string;
  okText?: string;
  cancelText?: string;
  onOk?: () => void;
  onCancel?: () => void;
  okButtonProps?: React.ButtonHTMLAttributes<HTMLButtonElement>;
  cancelButtonProps?: React.ButtonHTMLAttributes<HTMLButtonElement>;
  confirmLoading?: boolean;
  isAlert?: boolean;
}

export interface ConfirmModalProps extends Omit<ModalProps, 'children' | 'footer'> {
  content: React.ReactNode;
  type?: 'info' | 'success' | 'warning' | 'error' | 'confirm';
  icon?: React.ReactNode;
}

export type ConfirmFunction = (props: ConfirmModalProps) => { destroy: () => void };

export interface ConfirmType extends ConfirmFunction {
  info: ConfirmFunction;
  success: ConfirmFunction;
  warning: ConfirmFunction;
  error: ConfirmFunction;
  confirm: ConfirmFunction;
}
