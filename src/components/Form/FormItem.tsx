import React, { forwardRef } from 'react';
import type { FormItemProps } from './types';
import { cn } from '@utils/cn';
import { generateId } from '@a11y';
import styles from './Form.module.css';

export const FormItem = forwardRef<HTMLDivElement, FormItemProps>(
  ({ label, required, error, helperText, disabled, children, htmlFor }, ref) => {
    const labelId = generateId('form-label');
    const errorId = generateId('form-error');
    const helperId = generateId('form-helper');

    const forId = htmlFor || labelId;

    const describedBy = [
      error ? errorId : null,
      helperText ? helperId : null,
    ]
      .filter(Boolean)
      .join(' ');

    const clonedChild = React.cloneElement(children, {
      id: forId,
      'aria-labelledby': label ? labelId : undefined,
      'aria-describedby': describedBy || undefined,
      'aria-invalid': Boolean(error),
      'aria-required': required,
      disabled: disabled || children.props.disabled,
    });

    return (
      <div ref={ref} className={styles.formItem}>
        {label ? (
          <label
            id={labelId}
            htmlFor={forId}
            className={cn(styles.label, disabled && styles.labelDisabled)}
          >
            {label}
            {required && <span className={styles.required}>*</span>}
          </label>
        ) : null}
        <div className={styles.fieldWrapper}>{clonedChild}</div>
        {error ? (
          <span id={errorId} className={styles.errorText} role="alert">
            {error}
          </span>
        ) : null}
        {helperText && !error ? (
          <span id={helperId} className={styles.helperText}>
            {helperText}
          </span>
        ) : null}
      </div>
    );
  },
);

FormItem.displayName = 'FormItem';
