import React, { forwardRef, useCallback, useMemo, useRef, useState } from 'react';
import type { SelectProps, SelectOption } from './types';
import { cn } from '@utils/cn';
import { useControllableState } from '@hooks/useControllableState';
import { useEscapeKey, generateId, getLiveRegionProps } from '@a11y';
import { useFloating, flip, shift, autoUpdate } from '@floating-ui/react';
import styles from './Select.module.css';

const ChevronDown: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <polyline points="6 9 12 15 18 9" />
  </svg>
);

const ClearIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

export const Select = forwardRef<HTMLButtonElement, SelectProps>(
  (
    {
      options,
      size = 'md',
      value,
      defaultValue,
      placeholder = '请选择',
      onChange,
      error = false,
      required = false,
      label,
      helperText,
      disabled = false,
      clearable = false,
      className,
    },
    ref,
  ) => {
    const selectId = generateId('select');
    const labelId = generateId('select-label');
    const listboxId = generateId('select-listbox');
    const liveRegionId = generateId('select-live');

    const [internalValue, setInternalValue] = useControllableState(
      value,
      defaultValue ?? '',
      onChange ? (v) => {
        const option = options.find((o) => o.value === v);
        if (option) onChange(v, option);
      } : undefined,
    );

    const [isOpen, setIsOpen] = useState(false);
    const [activeIndex, setActiveIndex] = useState(0);
    const triggerRef = useRef<HTMLDivElement>(null);

    const { x, y, strategy, refs } = useFloating({
      open: isOpen,
      onOpenChange: setIsOpen,
      placement: 'bottom-start',
      middleware: [flip(), shift()],
      whileElementsMounted: autoUpdate,
    });

    useEscapeKey(() => setIsOpen(false), isOpen);

    const selectedOption = useMemo(
      () => options.find((o) => o.value === internalValue),
      [internalValue, options],
    );

    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent<HTMLButtonElement>) => {
        if (disabled) return;

        const enabledOptions = options.filter((o) => !o.disabled);

        switch (e.key) {
          case 'Enter':
          case ' ':
            e.preventDefault();
            if (!isOpen) {
              setIsOpen(true);
            } else {
              const option = enabledOptions[activeIndex];
              if (option) {
                setInternalValue(option.value);
                setIsOpen(false);
              }
            }
            break;
          case 'ArrowDown':
            e.preventDefault();
            if (!isOpen) {
              setIsOpen(true);
            } else {
              setActiveIndex((prev) => {
                const next = (prev + 1) % enabledOptions.length;
                return next;
              });
            }
            break;
          case 'ArrowUp':
            e.preventDefault();
            if (isOpen) {
              setActiveIndex((prev) => {
                const next = (prev - 1 + enabledOptions.length) % enabledOptions.length;
                return next;
              });
            }
            break;
          case 'Escape':
            setIsOpen(false);
            break;
          default:
            break;
        }
      },
      [disabled, options, isOpen, activeIndex, setInternalValue],
    );

    const handleOptionClick = useCallback(
      (option: SelectOption) => {
        if (option.disabled) return;
        setInternalValue(option.value);
        setIsOpen(false);
      },
      [setInternalValue],
    );

    const handleClear = useCallback(
      (e: React.MouseEvent) => {
        e.stopPropagation();
        setInternalValue('');
      },
      [setInternalValue],
    );

    const triggerClasses = cn(
      styles.trigger,
      styles[size],
      Boolean(error) && styles.error,
      className,
    );

    const hasError = Boolean(error);
    const errorMessage = typeof error === 'string' ? error : undefined;

    return (
      <div className={styles.wrapper}>
        {label ? (
          <label id={labelId} htmlFor={selectId} className={styles.label}>
            {label}
            {required && <span className={styles.required}>*</span>}
          </label>
        ) : null}

        <div ref={triggerRef} style={{ position: 'relative' }}>
          <button
            ref={ref}
            id={selectId}
            type="button"
            className={triggerClasses}
            disabled={disabled}
            onClick={() => !disabled && setIsOpen((prev) => !prev)}
            onKeyDown={handleKeyDown}
            aria-haspopup="listbox"
            aria-expanded={isOpen}
            aria-labelledby={label ? labelId : undefined}
            aria-controls={isOpen ? listboxId : undefined}
          >
            <span className={cn(styles.value, !selectedOption && styles.placeholder)}>
              {selectedOption ? selectedOption.label : placeholder}
            </span>
            {clearable && selectedOption ? (
              <span className={styles.clearBtn} onClick={handleClear} role="button" aria-label="清除选择">
                <ClearIcon />
              </span>
            ) : null}
            <ChevronDown className={cn(styles.arrow, isOpen && styles.open)} />
          </button>

          {isOpen ? (
            <div
              ref={refs.setFloating}
              id={listboxId}
              role="listbox"
              className={styles.dropdown}
              style={{
                position: strategy,
                top: y ?? 0,
                left: x ?? 0,
                width: refs.reference.current?.getBoundingClientRect().width,
              }}
            >
              {options.map((option, index) => (
                <div
                  key={option.value}
                  id={`${selectId}-option-${option.value}`}
                  role="option"
                  aria-selected={option.value === internalValue}
                  aria-disabled={option.disabled}
                  className={cn(
                    styles.option,
                    option.value === internalValue && styles.active,
                    option.disabled && styles.disabled,
                  )}
                  onClick={() => handleOptionClick(option)}
                  onMouseEnter={() => setActiveIndex(index)}
                >
                  {option.icon ? <span className={styles.optionIcon}>{option.icon}</span> : null}
                  <span>{option.label}</span>
                </div>
              ))}
            </div>
          ) : null}
        </div>

        <span
          id={liveRegionId}
          className="sr-only"
          {...getLiveRegionProps()}
          aria-hidden={!isOpen}
        >
          {isOpen ? `已展开，${options.length}个选项，使用方向键导航` : null}
        </span>

        {(helperText || errorMessage) ? (
          <div className={cn(styles.helperText, hasError && styles.error)}>
            {errorMessage || helperText}
          </div>
        ) : null}
      </div>
    );
  },
);

Select.displayName = 'Select';

export type { SelectProps, SelectOption };
