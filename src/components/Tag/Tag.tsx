import React, { forwardRef, useCallback } from 'react';
import type { TagProps } from './types';
import { cn } from '@utils/cn';
import { generateId } from '@a11y';
import styles from './Tag.module.css';

const CloseIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

export const Tag = forwardRef<HTMLSpanElement, TagProps>(
  (
    {
      children,
      color = 'default',
      size = 'md',
      closable = false,
      onClose,
      disabled = false,
      className,
      ...props
    },
    ref,
  ) => {
    const tagId = generateId('tag');

    const handleClose = useCallback(
      (e: React.MouseEvent) => {
        e.stopPropagation();
        if (!disabled && onClose) {
          onClose(e);
        }
      },
      [disabled, onClose],
    );

    const tagClasses = cn(
      styles.tag,
      styles[color],
      styles[size],
      disabled && styles.disabled,
      className,
    );

    return (
      <span
        ref={ref}
        id={tagId}
        className={tagClasses}
        role={closable ? 'button' : undefined}
        tabIndex={closable && !disabled ? 0 : undefined}
        aria-label={closable ? `${children}，点击移除` : undefined}
        aria-disabled={disabled || undefined}
        onClick={!disabled && closable ? handleClose : undefined}
        onKeyDown={
          !disabled && closable
            ? (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  handleClose(e as unknown as React.MouseEvent);
                }
              }
            : undefined
        }
        {...props}
      >
        <span className={styles.content}>{children}</span>
        {closable && !disabled ? (
          <span
            className={styles.closeBtn}
            onClick={handleClose}
            role="button"
            tabIndex={-1}
            aria-label="移除"
          >
            <CloseIcon />
          </span>
        ) : null}
      </span>
    );
  },
);

Tag.displayName = 'Tag';

export type { TagProps };
