import React, { forwardRef, useCallback, createContext, useContext } from 'react';
import type { RadioProps, RadioGroupProps, RadioOption } from './types';
import { cn } from '@utils/cn';
import { useControllableState } from '@hooks/useControllableState';
import { useRovingFocus, generateId } from '@a11y';
import styles from './Radio.module.css';

interface RadioGroupContextValue {
  name: string;
  value: string | undefined;
  disabled: boolean;
  onChange: (value: string) => void;
  getItemProps: (id: string) => ReturnType<typeof useRovingFocus>['getItemProps'];
}

const RadioGroupContext = createContext<RadioGroupContextValue | undefined>(undefined);

const useRadioGroup = () => {
  const context = useContext(RadioGroupContext);
  return context;
};

export const RadioGroup: React.FC<RadioGroupProps> = ({
  value,
  defaultValue,
  onChange,
  options,
  disabled = false,
  children,
  name,
  orientation = 'vertical',
  label,
  className,
}) => {
  const [groupValue, setGroupValue] = useControllableState(value, defaultValue ?? '', onChange);
  const groupName = name || generateId('radio-group');
  const labelId = generateId('radio-group-label');

  const optionValues = options?.map((o) => o.value) || [];
  const { getItemProps } = useRovingFocus(optionValues, orientation);

  const handleChange = useCallback(
    (itemValue: string) => {
      setGroupValue(itemValue);
    },
    [setGroupValue],
  );

  return (
    <div role="radiogroup" aria-labelledby={label ? labelId : undefined} className={className}>
      {label ? (
        <div id={labelId} className={styles.groupLabel}>
          {label}
        </div>
      ) : null}
      <RadioGroupContext.Provider
        value={{
          name: groupName,
          value: groupValue,
          disabled,
          onChange: handleChange,
          getItemProps,
        }}
      >
        <div className={cn(styles.group, orientation === 'vertical' && styles.groupVertical)}>
          {options
            ? options.map((option) => (
                <Radio
                  key={option.value}
                  value={option.value}
                  label={option.label}
                  disabled={option.disabled}
                />
              ))
            : children}
        </div>
      </RadioGroupContext.Provider>
    </div>
  );
};

export const Radio = forwardRef<HTMLInputElement, RadioProps>(
  (
    {
      size = 'md',
      disabled = false,
      checked,
      onChange,
      label,
      error = false,
      className,
      value,
      ...props
    },
    ref,
  ) => {
    const groupContext = useRadioGroup();
    const radioId = generateId('radio');
    const labelId = generateId('radio-label');

    const isInGroup = groupContext !== undefined;
    const hasError = Boolean(error);
    const errorMessage = typeof error === 'string' ? error : undefined;

    const isChecked = isInGroup ? groupContext.value === value : checked;
    const isDisabled = disabled || groupContext?.disabled || false;

    const handleChange = useCallback(
      (e: React.ChangeEvent<HTMLInputElement>) => {
        if (isDisabled) return;

        if (isInGroup) {
          groupContext.onChange(String(value));
        } else {
          onChange?.(e);
        }
      },
      [isDisabled, isInGroup, value, groupContext, onChange],
    );

    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent) => {
        if (isDisabled) return;
        if (e.key === ' ' || e.key === 'Enter') {
          e.preventDefault();
          const syntheticEvent = {
            target: { checked: true, value: value ?? '' },
          } as React.ChangeEvent<HTMLInputElement>;
          handleChange(syntheticEvent);
        }
      },
      [isDisabled, value, handleChange],
    );

    const rovingProps = isInGroup
      ? groupContext.getItemProps(String(value))
      : { tabIndex: isChecked ? 0 : -1 };

    return (
      <div>
        <label
          htmlFor={radioId}
          className={cn(styles.wrapper, isDisabled && styles.disabled, className)}
        >
          <input
            ref={ref}
            id={radioId}
            type="radio"
            className={styles.input}
            checked={isChecked}
            disabled={isDisabled}
            onChange={handleChange}
            onKeyDown={handleKeyDown}
            name={groupContext?.name}
            value={value}
            aria-checked={isChecked}
            aria-disabled={isDisabled}
            aria-labelledby={label ? labelId : undefined}
            aria-invalid={hasError}
            {...rovingProps}
            {...props}
          />
          <span
            className={cn(
              styles.radio,
              styles[size],
              hasError && styles.error,
            )}
            aria-hidden="true"
          />
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

Radio.displayName = 'Radio';
RadioGroup.displayName = 'RadioGroup';

export type { RadioProps, RadioGroupProps, RadioOption };
