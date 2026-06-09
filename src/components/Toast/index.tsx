import React, {
  createContext,
  useContext,
  useCallback,
  useState,
  useRef,
  useEffect,
} from 'react';
import type { ToastOptions, ToastContainerProps, ToastContextValue, ToastPosition } from './types';
import { cn } from '@utils/cn';
import { generateId, getLiveRegionProps } from '@a11y';
import { ToastItem } from './ToastItem';
import styles from './Toast.module.css';

const ToastContext = createContext<ToastContextValue | undefined>(undefined);

export const useToast = (): ToastContextValue => {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return context;
};

interface ToastState extends ToastOptions {
  isExiting?: boolean;
}

const positionClasses: Record<ToastPosition, string> = {
  'top-left': styles.topLeft,
  'top-center': styles.topCenter,
  'top-right': styles.topRight,
  'bottom-left': styles.bottomLeft,
  'bottom-center': styles.bottomCenter,
  'bottom-right': styles.bottomRight,
};

export const ToastContainer: React.FC<React.PropsWithChildren<ToastContainerProps>> = ({
  children,
  position = 'top-right',
  duration = 3000,
  limit = 5,
}) => {
  const [toasts, setToasts] = useState<ToastState[]>([]);
  const [exitingIds, setExitingIds] = useState<Set<string>>(new Set());
  const timeoutsRef = useRef<Map<string, ReturnType<typeof setTimeout>>(new Map());

  const toast = useCallback(
    (options: Omit<ToastOptions, 'id'>): string => {
      const id = generateId('toast');
      const newToast: ToastState = {
        id,
        type: 'default',
        duration,
        position,
        ...options,
      };

      setToasts((prev) => {
        const updated = [...prev, newToast];
        if (updated.length > limit) {
          const excess = updated.length - limit;
          return updated.slice(excess);
        }
        return updated;
      });

      return id;
    },
    [duration, position, limit],
  );

  const dismiss = useCallback((id: string) => {
    setExitingIds((prev) => new Set(prev).add(id));

    const existingTimeout = timeoutsRef.current.get(id);
    if (existingTimeout) {
      clearTimeout(existingTimeout);
    }

    const timeout = setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
      setExitingIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
      timeoutsRef.current.delete(id);
    }, 200);

    timeoutsRef.current.set(id, timeout);
  }, []);

  const dismissAll = useCallback(() => {
    toasts.forEach((t) => dismiss(t.id));
  }, [toasts, dismiss]);

  useEffect(() => {
    return () => {
      timeoutsRef.current.forEach((timeout) => clearTimeout(timeout));
    };
  }, []);

  const value = { toast, dismiss, dismissAll };

  const toastsByPosition = toasts.reduce((acc, t) => {
    const pos = t.position || position;
    if (!acc[pos]) {
      acc[pos] = [];
    }
    acc[pos]?.push(t);
    return acc;
  }, {} as Record<ToastPosition, ToastState[]>);

  return (
    <ToastContext.Provider value={value}>
      {children}
      {Object.entries(toastsByPosition).map(([pos, positionToasts]) => (
        <div
          key={pos}
          className={cn(styles.container, positionClasses[pos as ToastPosition])}
        >
          {positionToasts.map((t) => (
            <ToastItem
              key={t.id}
              {...t}
              isExiting={exitingIds.has(t.id)}
              onClose={() => dismiss(t.id)}
            />
          ))}
        </div>
      ))}
      <div className="sr-only" {...getLiveRegionProps()}>
        {toasts.length > 0 ? `${toasts.length}条通知` : ''}
      </div>
    </ToastContext.Provider>
  );
};

export const ToastProvider: React.FC<React.PropsWithChildren<ToastContainerProps>> = ({ children, ...props }) => (
  <ToastContainer {...props}>{children}</ToastContainer>
);

ToastContainer.displayName = 'ToastContainer';
ToastProvider.displayName = 'ToastProvider';

export type { ToastOptions, ToastType, ToastPosition };
