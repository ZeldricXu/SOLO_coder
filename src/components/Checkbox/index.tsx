import React, { forwardRef, useCallback, useEffect, useRef, createContext, useContext } from 'react';
import type { CheckboxProps, CheckboxGroupProps } from './types';
import { cn } from '@utils/cn';
import { useControllableState } from '@hooks/useControllableState';
import { generateId } from '@a11y';
import styles from './Checkbox.module.css';

const CheckIcon: React.FC<{ size: number }> = ({ size }) => (
  <svg className={styles.checkIcon} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
    <polyline points="20 6 9 17 4 12" />
  </svg>
);

interface CheckboxGroupContextValue {
  name: string;
  value: string[];
  disabled: boolean;
  onChange: (value: string) => void;
}

const CheckboxGroupContext = createContext<CheckboxGroupContextValue | undefined>(undefined);

const useCheckboxGroup = () => {
  const context = useContext(CheckboxGroupContext);
  return context;
};

export const CheckboxGroup: React.FC<CheckboxGroupProps> = ({
  value,
  defaultValue = [],
  onChange,
  disabled = false,
  children,
  name,
  className,
}) => {
  const [groupValue, setGroupValue] = useControllableState(value, defaultValue, onChange);
  const groupName = name || generateId('checkbox-group');

  const handleChange = useCallback(
    (itemValue: string) => {
      setGroupValue((prev) => {
        const prevArray = Array.isArray(prev) ? prev : [];
        const newValue = prevArray.includes(itemValue)
          ? prevArray.filter((v) => v !== itemValue)
          : [...prevArray, itemValue];
        return newValue;
      });
    },
    [setGroupValue],
  );

  return (
    <CheckboxGroupContext.Provider
      value={{ name: groupName, value: groupValue, disabled, onChange: handleChange }}
    >
      <div className={cn(styles.group, className)} role="group">
        {children}
      </div>
    </CheckboxGroupContext.Provider>
  );
};

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(
  (
    {
      size = 'md',
      disabled = false,
      checked,
      defaultChecked,
      onChange,
      label,
      indeterminate = false,
      error = false,
      className,
      value,
      ...props
    },
    ref,
  ) => {
    const inputRef = useRef<HTMLInputElement>(null);
    const groupContext = useCheckboxGroup();
    const checkboxId = generateId('checkbox');
    const labelId = generateId('checkbox-label');

    const isInGroup = groupContext !== undefined && value !== undefined;
    const hasError = Boolean(error);
    const errorMessage = typeof error === 'string' ? error : undefined;

    const [internalChecked, setInternalChecked] = useControllableState(
      checked,
      defaultChecked ?? false,
      onChange ? (v) => {
        const event = {
          target: { checked: v, value: value ?? '' },
        } as React.ChangeEvent<HTMLInputElement>;
        onChange?.(event);
      } : undefined,
    );

    const isChecked = isInGroup
      ? groupContext.value.includes(String(value))
      : internalChecked;

    const isDisabled = disabled || groupContext?.disabled || false;

    useEffect(() => {
      if (inputRef.current) {
        inputRef.current.indeterminate = indeterminate;
      }
    }, [indeterminate]);

    const handleChange = useCallback(
      (e: React.ChangeEvent<HTMLInputElement>) => {
        if (isDisabled) return;

        if (isInGroup && value !== undefined) {
          groupContext.onChange(String(value));
        } else {
          setInternalChecked(e.target.checked);
        }
      },
      [isDisabled, isInGroup, value, groupContext, setInternalChecked],
    );

    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent) => {
        if (isDisabled) return;
        if (e.key === ' ' || e.key === 'Enter') {
          e.preventDefault();
          const syntheticEvent = {
            target: { checked: !isChecked },
          } as React.ChangeEvent<HTMLInputElement>;
          handleChange(syntheticEvent);
        }
      },
      [isDisabled, isChecked, handleChange],
    );

    const iconSize = size === 'sm' ? 12 : size === 'md' ? 14 : 16;

    return (
      <div>
        <label
          htmlFor={checkboxId}
          className={cn(styles.wrapper, isDisabled && styles.disabled, className)}
        >
          <input
            ref={(node) => {
              inputRef.current = node;
              if (typeof ref === 'function') ref(node);
              else if (ref !== null) ref.current = node;
            }}
            id={checkboxId}
            type="checkbox"
            className={styles.input}
            checked={isChecked}
            disabled={isDisabled}
            onChange={handleChange}
            onKeyDown={handleKeyDown}
            name={groupContext?.name}
            value={value}
            aria-checked={indeterminate ? 'mixed' : isChecked}
            aria-disabled={isDisabled}
            aria-labelledby={label ? labelId : undefined}
            aria-invalid={hasError}
            {...props}
          />
          <span
            className={cn(
              styles.checkbox,
              styles[size],
              hasError && styles.error,
            )}
            aria-hidden="true"
          >
            <span className={styles.indeterminate} />
            <CheckIcon size={iconSize} />
          </span>
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
        {errorMessage ? <div className={styles.errorText} role="alert">{errorMessage}</div> : null}
      </div>
    );
  },
);

Checkbox.displayName = 'Checkbox';
CheckboxGroup.displayName = 'CheckboxGroup';

export type { CheckboxProps, CheckboxGroupProps };
