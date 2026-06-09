import React, { forwardRef, useCallback, useMemo, useState } from 'react';
import type { InputProps } from './types';
import { cn } from '@utils/cn';
import { getInputAriaProps, generateId } from '@a11y';
import styles from './Input.module.css';

export const Input = forwardRef<HTMLInputElement, InputProps>(
  (
    {
      type = 'text',
      size = 'md',
      disabled = false,
      error = false,
      required = false,
      className,
      placeholder,
      prefix,
      suffix,
      label,
      helperText,
      showCount = false,
      maxLength,
      value,
      defaultValue,
      onChange,
      'aria-label': ariaLabel,
      ...props
    },
    ref,
  ) => {
    const inputId = generateId('input');
    const labelId = generateId('input-label');
    const errorId = generateId('input-error');
    const helperId = generateId('input-helper');

    const [internalValue, setInternalValue] = useState<string>(
      String(defaultValue ?? ''),
    );

    const isControlled = value !== undefined;
    const currentValue = isControlled ? String(value) : internalValue;

    const handleChange = useCallback(
      (e: React.ChangeEvent<HTMLInputElement>) => {
        if (!isControlled) {
          setInternalValue(e.target.value);
        }
        onChange?.(e);
      },
      [isControlled, onChange],
    );

    const hasError = Boolean(error);
    const errorMessage = typeof error === 'string' ? error : undefined;

    const ariaProps = useMemo(
      () =>
        getInputAriaProps(disabled, required, hasError, hasError ? errorId : undefined),
      [disabled, required, hasError, errorId],
    );

    const describedBy = useMemo(() => {
      const ids: string[] = [];
      if (hasError && errorMessage) ids.push(errorId);
      if (helperText) ids.push(helperId);
      return ids.length > 0 ? ids.join(' ') : undefined;
    }, [hasError, errorMessage, helperText, errorId, helperId]);

    const wrapperClasses = cn(
      styles.inputWrapper,
      styles[size],
      hasError && styles.error,
      disabled && styles.disabled,
    );

    const inputClasses = cn(
      styles.input,
      size === 'sm' && styles.inputSm,
      size === 'md' && styles.inputMd,
      size === 'lg' && styles.inputLg,
      className,
    );

    return (
      <div className={styles.wrapper}>
        {label ? (
          <label id={labelId} htmlFor={inputId} className={styles.label}>
            {label}
            {required && <span className={styles.required}>*</span>}
          </label>
        ) : null}

        <div className={wrapperClasses}>
          {prefix ? <span className={styles.prefix}>{prefix}</span> : null}
          <input
            ref={ref}
            id={inputId}
            type={type}
            className={inputClasses}
            disabled={disabled}
            placeholder={placeholder}
            value={isControlled ? value : internalValue}
            onChange={handleChange}
            maxLength={maxLength}
            aria-label={ariaLabel}
            aria-labelledby={label ? labelId : undefined}
            aria-describedby={describedBy}
            {...ariaProps}
            {...props}
          />
          {suffix ? <span className={styles.suffix}>{suffix}</span> : null}
          {showCount && maxLength ? (
            <span className={styles.suffix}>
              <span className={styles.counter}>
                {currentValue.length}/{maxLength}
              </span>
            </span>
          ) : null}
        </div>

        {(helperText || errorMessage) ? (
          <div className={cn(styles.helperText, hasError && styles.error)}>
            {errorMessage ? (
              <span id={errorId} role="alert">
                {errorMessage}
              </span>
            ) : null}
            {helperText && !errorMessage ? (
              <span id={helperId}>{helperText}</span>
            ) : null}
          </div>
        ) : null}
      </div>
    );
  },
);

Input.displayName = 'Input';

export type { InputProps, InputType };
