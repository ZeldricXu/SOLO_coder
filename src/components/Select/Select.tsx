import React, { forwardRef, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { SelectProps, SelectOption } from './types';
import { cn } from '@utils/cn';
import { useControllableState } from '@hooks/useControllableState';
import { useEscapeKey, generateId, getLiveRegionProps } from '@a11y';
import { useFloating, flip, shift, autoUpdate, useDismiss } from '@floating-ui/react';
import { Tag } from '@components/Tag';
import { Checkbox } from '@components/Checkbox';
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
      multi = false,
      maxTagCount,
      className,
    },
    ref,
  ) => {
    const selectId = generateId('select');
    const labelId = generateId('select-label');
    const listboxId = generateId('select-listbox');
    const liveRegionId = generateId('select-live');

    const defaultInternalValue = multi ? [] : '';

    const [internalValue, setInternalValue] = useControllableState(
      value,
      defaultValue ?? defaultInternalValue,
      onChange ? (v) => {
        if (multi) {
          const selectedOptions = options.filter((o) => (v as string[]).includes(o.value));
          onChange(v as string[], selectedOptions);
        } else {
          const option = options.find((o) => o.value === v);
          onChange(v as string, option ?? null);
        }
      } : undefined,
    );

    const [isOpen, setIsOpen] = useState(false);
    const [activeIndex, setActiveIndex] = useState(0);
    const triggerRef = useRef<HTMLDivElement>(null);

    const { x, y, strategy, refs, context } = useFloating({
      open: isOpen,
      onOpenChange: setIsOpen,
      placement: 'bottom-start',
      middleware: [flip(), shift()],
      whileElementsMounted: autoUpdate,
    });

    useDismiss(context, {
      outsidePress: true,
    });

    useEscapeKey(() => setIsOpen(false), isOpen);

    const selectedValues = useMemo(
      () => (multi ? (Array.isArray(internalValue) ? internalValue : []) : []),
      [internalValue, multi],
    );

    const selectedOption = useMemo(
      () => (!multi ? options.find((o) => o.value === internalValue) : null),
      [internalValue, options, multi],
    );

    const selectedOptions = useMemo(
      () => (multi ? options.filter((o) => selectedValues.includes(o.value)) : []),
      [options, selectedValues, multi],
    );

    const isAllSelected = useMemo(() => {
      if (!multi) return false;
      const enabledOptions = options.filter((o) => !o.disabled);
      return enabledOptions.length > 0 && enabledOptions.every((o) => selectedValues.includes(o.value));
    }, [multi, options, selectedValues]);

    const isIndeterminate = useMemo(() => {
      if (!multi) return false;
      const enabledOptions = options.filter((o) => !o.disabled);
      const selectedEnabled = enabledOptions.filter((o) => selectedValues.includes(o.value));
      return selectedEnabled.length > 0 && selectedEnabled.length < enabledOptions.length;
    }, [multi, options, selectedValues]);

    const handleToggleOption = useCallback(
      (option: SelectOption) => {
        if (option.disabled) return;

        if (multi) {
          setInternalValue((prev) => {
            const prevArray = Array.isArray(prev) ? prev : [];
            const newValue = prevArray.includes(option.value)
              ? prevArray.filter((v) => v !== option.value)
              : [...prevArray, option.value];
            return newValue;
          });
        } else {
          setInternalValue(option.value);
          setIsOpen(false);
        }
      },
      [multi, setInternalValue],
    );

    const handleSelectAll = useCallback(() => {
      if (!multi) return;
      const enabledValues = options.filter((o) => !o.disabled).map((o) => o.value);
      if (isAllSelected) {
        setInternalValue([]);
      } else {
        setInternalValue(enabledValues);
      }
    }, [multi, options, isAllSelected, setInternalValue]);

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
                handleToggleOption(option);
                if (!multi) {
                  setIsOpen(false);
                }
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
      [disabled, options, isOpen, activeIndex, multi, handleToggleOption],
    );

    const handleOptionClick = useCallback(
      (option: SelectOption) => {
        handleToggleOption(option);
      },
      [handleToggleOption],
    );

    const handleClear = useCallback(
      (e: React.MouseEvent) => {
        e.stopPropagation();
        if (multi) {
          setInternalValue([]);
          if (onChange) {
            onChange([], []);
          }
        } else {
          setInternalValue('');
          if (onChange) {
            onChange('', null);
          }
        }
      },
      [multi, setInternalValue, onChange],
    );

    const handleTagClose = useCallback(
      (valueToRemove: string) => (e: React.MouseEvent) => {
        e.stopPropagation();
        if (multi) {
          setInternalValue((prev) => {
            const prevArray = Array.isArray(prev) ? prev : [];
            return prevArray.filter((v) => v !== valueToRemove);
          });
        }
      },
      [multi, setInternalValue],
    );

    const displayTags = useMemo(() => {
      if (!multi) return [];
      if (maxTagCount === undefined || maxTagCount >= selectedOptions.length) {
        return selectedOptions.map((opt) => ({
          ...opt,
          isOverflow: false,
        }));
      }
      const visibleTags = selectedOptions.slice(0, maxTagCount);
      const overflowCount = selectedOptions.length - maxTagCount;
      return [
        ...visibleTags.map((opt) => ({ ...opt, isOverflow: false })),
        {
          value: 'overflow',
          label: `+${overflowCount}`,
          isOverflow: true,
        } as SelectOption & { isOverflow: boolean },
      ];
    }, [multi, maxTagCount, selectedOptions]);

    const hasValue = multi ? selectedValues.length > 0 : !!selectedOption;

    const triggerClasses = cn(
      styles.trigger,
      styles[size],
      Boolean(error) && styles.error,
      multi && styles.multiTrigger,
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

        <div ref={refs.setReference} style={{ position: 'relative' }}>
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
            aria-invalid={hasError}
            aria-multiselectable={multi || undefined}
          >
            {multi ? (
              <div className={styles.tagsContainer}>
                {selectedOptions.length === 0 ? (
                  <span className={styles.placeholder}>{placeholder}</span>
                ) : (
                  displayTags.map((tag, index) =>
                    tag.isOverflow ? (
                      <Tag key="overflow" size="sm" color="default" className={styles.tag}>
                        {tag.label}
                      </Tag>
                    ) : (
                      <Tag
                        key={tag.value}
                        size="sm"
                        color="primary"
                        closable
                        onClose={handleTagClose(tag.value)}
                        className={styles.tag}
                      >
                        {tag.label}
                      </Tag>
                    ),
                  )
                )}
              </div>
            ) : (
              <span className={cn(styles.value, !selectedOption && styles.placeholder)}>
                {selectedOption ? selectedOption.label : placeholder}
              </span>
            )}
            {clearable && hasValue ? (
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
              aria-multiselectable={multi || undefined}
            >
              {multi && options.length > 0 ? (
                <div className={styles.selectAllRow}>
                  <Checkbox
                    checked={isAllSelected}
                    indeterminate={isIndeterminate}
                    onChange={handleSelectAll}
                    label={isAllSelected ? '取消全选' : '全选'}
                    aria-label={isAllSelected ? '取消全选' : '全选'}
                  />
                </div>
              ) : null}
              {options.length === 0 ? (
                <div className={styles.emptyState}>暂无数据</div>
              ) : (
                options.map((option, index) => {
                  const enabledIndex = options
                    .slice(0, index + 1)
                    .filter((o) => !o.disabled).length - 1;
                  const isSelected = multi
                    ? selectedValues.includes(option.value)
                    : option.value === internalValue;
                  return (
                  <div
                    key={option.value}
                    id={`${selectId}-option-${option.value}`}
                    role="option"
                    aria-selected={isSelected}
                    aria-disabled={option.disabled}
                    className={cn(
                      styles.option,
                      isSelected && styles.active,
                      option.disabled && styles.disabled,
                      multi && styles.multiOption,
                    )}
                    onClick={() => handleOptionClick(option)}
                    onMouseEnter={() => !option.disabled && setActiveIndex(enabledIndex)}
                  >
                    {multi ? (
                      <Checkbox
                        checked={isSelected}
                        disabled={option.disabled}
                      />
                    ) : null}
                    {option.icon ? <span className={styles.optionIcon}>{option.icon}</span> : null}
                    <span>{option.label}</span>
                  </div>
                  );
                })
              )}
            </div>
          ) : null}
        </div>

        <span
          id={liveRegionId}
          className="sr-only"
          {...getLiveRegionProps()}
          aria-hidden={!isOpen}
        >
          {isOpen
            ? multi
              ? `已展开多选下拉，${options.length}个选项，已选择${selectedValues.length}个，使用方向键导航，Enter键选择`
              : `已展开，${options.length}个选项，使用方向键导航`
            : null}
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
