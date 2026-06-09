import React, { forwardRef, useCallback, useRef, useState, useEffect } from 'react';
import type { DropdownProps, DropdownMenuItem } from './types';
import { cn } from '@utils/cn';
import { useEscapeKey, generateId, getMenuAriaProps, getMenuItemAriaProps } from '@a11y';
import { useFloating, flip, shift, autoUpdate } from '@floating-ui/react';
import { Button } from '@components/Button';
import styles from './Dropdown.module.css';

const ChevronDown: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <polyline points="6 9 12 15 18 9" />
  </svg>
);

const ChevronRight: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <polyline points="9 6 15 12 9 18" />
  </svg>
);

export const Dropdown = forwardRef<HTMLDivElement, DropdownProps>(
  (
    {
      items,
      trigger,
      triggerMode = 'click',
      placement = 'bottom-start',
      disabled = false,
      onOpenChange,
      onSelect,
      closeOnSelect = true,
      className,
      style,
    },
    ref,
  ) => {
    const menuId = generateId('dropdown-menu');
    const triggerId = generateId('dropdown-trigger');

    const [isOpen, setIsOpen] = useState(false);
    const [activeIndex, setActiveIndex] = useState(-1);
    const [openSubmenuKey, setOpenSubmenuKey] = useState<string | null>(null);
    const hoverTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const { x, y, strategy, refs } = useFloating({
      open: isOpen,
      onOpenChange: (open) => {
        setIsOpen(open);
        onOpenChange?.(open);
        if (!open) {
          setActiveIndex(-1);
          setOpenSubmenuKey(null);
        }
      },
      placement,
      middleware: [flip(), shift()],
      whileElementsMounted: autoUpdate,
    });

    useEscapeKey(() => setIsOpen(false), isOpen);

    useEffect(() => {
      const handleClickOutside = (e: MouseEvent) => {
        const target = e.target as Node;
        const floatingEl = refs.floating.current;
        const referenceEl = refs.reference.current;
        if (
          floatingEl &&
          referenceEl &&
          !floatingEl.contains(target) &&
          !referenceEl.contains(target)
        ) {
          setIsOpen(false);
        }
      };

      if (isOpen) {
        document.addEventListener('mousedown', handleClickOutside);
      }
      return () => {
        document.removeEventListener('mousedown', handleClickOutside);
      };
    }, [isOpen, refs]);

    const enabledItems = items.filter((item) => item.type !== 'divider' && !item.disabled);

    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent) => {
        if (disabled || !isOpen) return;

        switch (e.key) {
          case 'ArrowDown':
            e.preventDefault();
            setActiveIndex((prev) => {
              const next = prev === -1 ? 0 : (prev + 1) % enabledItems.length;
              return next;
            });
            break;
          case 'ArrowUp':
            e.preventDefault();
            setActiveIndex((prev) => {
              const next = prev === -1 ? enabledItems.length - 1 : (prev - 1 + enabledItems.length) % enabledItems.length;
              return next;
            });
            break;
          case 'Enter':
          case ' ':
            e.preventDefault();
            if (activeIndex >= 0) {
              const item = enabledItems[activeIndex];
              if (item && !item.disabled) {
                handleItemClick(item);
              }
            }
            break;
          case 'Tab':
            setIsOpen(false);
            break;
          default:
            break;
        }
      },
      [disabled, isOpen, activeIndex, enabledItems],
    );

    const handleTriggerClick = useCallback(() => {
      if (disabled) return;
      if (triggerMode === 'click') {
        setIsOpen((prev) => !prev);
      }
    }, [disabled, triggerMode]);

    const handleMouseEnter = useCallback(() => {
      if (disabled) return;
      if (triggerMode === 'hover') {
        if (hoverTimerRef.current) {
          clearTimeout(hoverTimerRef.current);
        }
        setIsOpen(true);
      }
    }, [disabled, triggerMode]);

    const handleMouseLeave = useCallback(() => {
      if (disabled) return;
      if (triggerMode === 'hover') {
        hoverTimerRef.current = setTimeout(() => {
          setIsOpen(false);
        }, 150);
      }
    }, [disabled, triggerMode]);

    const handleItemClick = useCallback(
      (item: DropdownMenuItem) => {
        if (item.disabled || item.type === 'divider') return;

        item.onClick?.();
        onSelect?.(item.key, item);

        if (item.children) {
          setOpenSubmenuKey(openSubmenuKey === item.key ? null : item.key);
        } else if (closeOnSelect) {
          setIsOpen(false);
        }
      },
      [onSelect, closeOnSelect, openSubmenuKey],
    );

    const renderItem = (item: DropdownMenuItem, index: number, level = 0) => {
      if (item.type === 'divider') {
        return <div key={item.key} className={styles.divider} role="separator" />;
      }

      const enabledIndex = enabledItems.findIndex((ei) => ei.key === item.key);
      const isActive = enabledIndex === activeIndex;

      return (
        <div
          key={item.key}
          {...getMenuItemAriaProps(item.disabled, item.children ? 'menu' : undefined)}
          className={cn(
            styles.item,
            isActive && styles.active,
            item.disabled && styles.disabled,
            item.danger && styles.danger,
          )}
          onClick={() => handleItemClick(item)}
          onMouseEnter={() => setActiveIndex(enabledIndex)}
          tabIndex={-1}
        >
          {item.icon ? <span className={styles.itemIcon}>{item.icon}</span> : null}
          <span className={styles.itemLabel}>{item.label}</span>
          {item.shortcut ? <span className={styles.shortcut}>{item.shortcut}</span> : null}
          {item.children ? <ChevronRight className={styles.submenuArrow} /> : null}
        </div>
      );
    };

    const ariaProps = getMenuAriaProps(isOpen);

    return (
      <div
        ref={ref}
        className={cn(styles.wrapper, className)}
        style={style}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
      >
        <div
          ref={refs.setReference}
          id={triggerId}
          className={styles.trigger}
          onClick={handleTriggerClick}
          onKeyDown={handleKeyDown}
          aria-haspopup="menu"
          aria-expanded={isOpen}
          aria-controls={isOpen ? menuId : undefined}
          tabIndex={disabled ? -1 : 0}
          role="button"
        >
          {trigger || (
            <Button variant="secondary" size="md" disabled={disabled}>
              菜单
              <ChevronDown className={cn(styles.arrow, isOpen && styles.arrowOpen)} />
            </Button>
          )}
        </div>

        {isOpen ? (
          <div
            ref={refs.setFloating}
            id={menuId}
            {...ariaProps}
            className={styles.menu}
            style={{
              position: strategy,
              top: y ?? 0,
              left: x ?? 0,
              minWidth: refs.reference.current?.getBoundingClientRect().width,
            }}
            onKeyDown={handleKeyDown}
            onMouseEnter={() => {
              if (hoverTimerRef.current) {
                clearTimeout(hoverTimerRef.current);
              }
            }}
            onMouseLeave={handleMouseLeave}
          >
            {items.map((item, index) => renderItem(item, index))}
          </div>
        ) : null}
      </div>
    );
  },
);

Dropdown.displayName = 'Dropdown';

export type { DropdownProps, DropdownMenuItem };
