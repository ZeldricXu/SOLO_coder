import React, { forwardRef, useEffect, useCallback } from 'react';
import type { ToastProps } from './types';
import { cn } from '@utils/cn';
import styles from './Toast.module.css';

const SuccessIcon: React.FC = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
    <polyline points="20 6 9 17 4 12" />
  </svg>
);

const ErrorIcon: React.FC = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

const WarningIcon: React.FC = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
    <line x1="12" y1="9" x2="12" y2="13" />
    <line x1="12" y1="17" x2="12.01" y2="17" />
  </svg>
);

const InfoIcon: React.FC = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <circle cx="12" cy="12" r="10" />
    <line x1="12" y1="16" x2="12" y2="12" />
    <line x1="12" y1="8" x2="12.01" y2="8" />
  </svg>
);

const CloseIcon: React.FC = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

const iconMap: Record<string, React.FC> = {
  success: SuccessIcon,
  error: ErrorIcon,
  warning: WarningIcon,
  info: InfoIcon,
  default: InfoIcon,
};

export const ToastItem = forwardRef<HTMLDivElement, ToastProps & { isExiting?: boolean }>(
  ({ type, message, duration = 3000, onClose, action, isExiting }, ref) => {
    const Icon = iconMap[type] || InfoIcon;

    useEffect(() => {
      if (duration === Infinity || duration <= 0) return;

      const timer = setTimeout(() => {
        onClose();
      }, duration);

      return () => clearTimeout(timer);
    }, [duration, onClose]);

    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent) => {
        if (e.key === 'Escape') {
          onClose();
        }
      },
      [onClose],
    );

    return (
      <div
        ref={ref}
        className={cn(styles.toast, isExiting && styles.exiting)}
        role="status"
        aria-live={type === 'error' ? 'assertive' : 'polite'}
        aria-atomic="true"
        tabIndex={0}
        onKeyDown={handleKeyDown}
      >
        <span className={cn(styles.icon, styles[type])} aria-hidden="true">
          <Icon />
        </span>
        <div className={styles.content}>
          <span className={styles.message}>{message}</span>
          {action ? (
            <button
              className={styles.actionBtn}
              onClick={() => {
                action.onClick();
                onClose();
              }}
            >
              {action.label}
            </button>
          ) : null}
        </div>
        <button
          className={styles.closeBtn}
          onClick={onClose}
          aria-label="关闭通知"
          type="button"
        >
          <CloseIcon />
        </button>
      </div>
    );
  },
);

ToastItem.displayName = 'ToastItem';
