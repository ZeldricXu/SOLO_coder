export type ToastType = 'success' | 'error' | 'warning' | 'info' | 'default';

export type ToastPosition =
  | 'top-left'
  | 'top-center'
  | 'top-right'
  | 'bottom-left'
  | 'bottom-center'
  | 'bottom-right';

export interface ToastOptions {
  id: string;
  type: ToastType;
  message: React.ReactNode;
  duration?: number;
  position?: ToastPosition;
  onClose?: () => void;
  action?: {
    label: string;
    onClick: () => void;
  };
}

export interface ToastProps extends ToastOptions {
  onClose: () => void;
}

export interface ToastContainerProps {
  position?: ToastPosition;
  duration?: number;
  limit?: number;
}

export interface ToastContextValue {
  toast: (options: Omit<ToastOptions, 'id'>) => string;
  dismiss: (id: string) => void;
  dismissAll: () => void;
}
