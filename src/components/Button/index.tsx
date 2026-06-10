import React, { forwardRef, useCallback } from 'react';
import type { ButtonProps } from './types';
import { cn } from '@utils/cn';
import { getButtonAriaProps, generateId } from '@a11y';
import styles from './Button.module.css';

const Spinner: React.FC = () => <span className={styles.spinner} aria-hidden="true" />;

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      variant = 'primary',
      size = 'md',
      type = 'button',
      disabled = false,
      loading = false,
      fullWidth = false,
      leftIcon,
      rightIcon,
      className,
      children,
      onClick,
      onKeyDown,
      'aria-label': ariaLabel,
      ...props
    },
    ref,
  ) => {
    const buttonId = generateId('button');

    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent<HTMLButtonElement>) => {
        if ((e.key === 'Enter' || e.key === ' ') && !disabled && !loading) {
          e.preventDefault();
          onClick?.(e as unknown as React.MouseEvent<HTMLButtonElement>);
        }
        onKeyDown?.(e);
      },
      [disabled, loading, onClick, onKeyDown],
    );

    const ariaProps = getButtonAriaProps(disabled || loading, ariaLabel);

    const classes = cn(
      styles.base,
      styles[size],
      styles[variant],
      fullWidth && styles.fullWidth,
      loading && styles.loading,
      className,
    );

    return (
      <button
        ref={ref}
        id={buttonId}
        type={type}
        className={classes}
        disabled={disabled || loading}
        onClick={loading ? undefined : onClick}
        onKeyDown={handleKeyDown}
        aria-busy={loading ? true : undefined}
        {...ariaProps}
        {...props}
      >
        {loading ? (
          <Spinner />
        ) : (
          leftIcon
        )}
        <span>{children}</span>
        {rightIcon}
      </button>
    );
  },
);

Button.displayName = 'Button';

export type { ButtonProps, ButtonVariant, ButtonType };
