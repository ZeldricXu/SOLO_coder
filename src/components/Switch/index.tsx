import React, { forwardRef, useCallback } from 'react';
import type { SwitchProps } from './types';
import { cn } from '@utils/cn';
import { useControllableState } from '@hooks/useControllableState';
import { generateId, getButtonAriaProps } from '@a11y';
import styles from './Switch.module.css';

export const Switch = forwardRef<HTMLButtonElement, SwitchProps>(
  (
    {
      size = 'md',
      disabled = false,
      loading = false,
      checked,
      defaultChecked,
      onChange,
      label,
      className,
      onClick,
      onKeyDown,
      ...props
    },
    ref,
  ) => {
    const switchId = generateId('switch');
    const labelId = generateId('switch-label');

    const [isChecked, setIsChecked] = useControllableState(
      checked,
      defaultChecked ?? false,
      onChange,
    );

    const isDisabled = disabled || loading;

    const handleClick = useCallback(
      (e: React.MouseEvent<HTMLButtonElement>) => {
        if (isDisabled) return;
        e.preventDefault();
        setIsChecked((prev) => !prev);
        onClick?.(e);
      },
      [isDisabled, setIsChecked, onClick],
    );

    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent<HTMLButtonElement>) => {
        if (isDisabled) return;

        if (e.key === ' ' || e.key === 'Enter') {
          e.preventDefault();
          setIsChecked((prev) => !prev);
        }
        onKeyDown?.(e);
      },
      [isDisabled, setIsChecked, onKeyDown],
    );

    const ariaProps = getButtonAriaProps(isDisabled, undefined, isChecked);

    const switchClasses = cn(
      styles.switch,
      styles[size],
      isChecked && styles.checked,
      loading && styles.loading,
      className,
    );

    return (
      <label
        htmlFor={switchId}
        className={cn(styles.wrapper, isDisabled && styles.disabled)}
      >
        <button
          ref={ref}
          id={switchId}
          type="button"
          role="switch"
          className={switchClasses}
          disabled={isDisabled}
          onClick={handleClick}
          onKeyDown={handleKeyDown}
          aria-checked={isChecked}
          aria-labelledby={label ? labelId : undefined}
          {...ariaProps}
          {...props}
        >
          <span className={styles.thumb}>
            {loading ? <span className={styles.spinner} aria-hidden="true" /> : null}
          </span>
        </button>
        {label ? (
          <span
            id={labelId}
            className={cn(
              styles.label,
              size === 'sm' && styles.smLabel,
              size === 'md' && styles.mdLabel,
              size === 'lg' && styles.lgLabel,
            )}
          >
            {label}
          </span>
        ) : null}
      </label>
    );
  },
);

Switch.displayName = 'Switch';

export type { SwitchProps };
