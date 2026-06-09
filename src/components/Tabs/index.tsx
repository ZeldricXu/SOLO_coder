import React, { forwardRef, useCallback, useMemo, useRef, useState, useEffect } from 'react';
import type { TabsProps, TabPaneProps, TabItem } from './types';
import { cn } from '@utils/cn';
import { useControllableState } from '@hooks/useControllableState';
import { useRovingFocus, generateId } from '@a11y';
import styles from './Tabs.module.css';

const TabPane: React.FC<TabPaneProps> = ({ tabKey, children, className, style }) => {
  return (
    <div
      role="tabpanel"
      id={`tabpanel-${tabKey}`}
      aria-labelledby={`tab-${tabKey}`}
      className={cn(styles.tabPanel, className)}
      style={style}
    >
      {children}
    </div>
  );
};

export const Tabs = forwardRef<HTMLDivElement, TabsProps>(
  (
    {
      items,
      activeKey,
      defaultActiveKey,
      onChange,
      size = 'md',
      variant = 'line',
      placement = 'top',
      disabled = false,
      children,
      className,
      style,
    },
    ref,
  ) => {
    const tabsId = generateId('tabs');
    const tabListId = generateId('tablist');

    const [internalActiveKey, setInternalActiveKey] = useControllableState(
      activeKey,
      defaultActiveKey ?? (items[0]?.key || ''),
      onChange,
    );

    const [indicatorStyle, setIndicatorStyle] = useState<React.CSSProperties>({});
    const tabRefs = useRef<Map<string, HTMLButtonElement>>(new Map());

    const enabledKeys = useMemo(
      () => items.filter((item) => !item.disabled).map((item) => item.key),
      [items],
    );

    const orientation = placement === 'left' || placement === 'right' ? 'vertical' : 'horizontal';
    const { getItemProps } = useRovingFocus(enabledKeys, orientation);

    useEffect(() => {
      const activeTab = tabRefs.current.get(internalActiveKey);
      if (activeTab && variant === 'line') {
        const rect = activeTab.getBoundingClientRect();
        if (orientation === 'horizontal') {
          setIndicatorStyle({
            left: activeTab.offsetLeft,
            width: rect.width,
          });
        } else {
          setIndicatorStyle({
            top: activeTab.offsetTop,
            height: rect.height,
          });
        }
      }
    }, [internalActiveKey, variant, orientation, items]);

    const handleTabClick = useCallback(
      (key: string, item: TabItem) => {
        if (disabled || item.disabled) return;
        setInternalActiveKey(key);
      },
      [disabled, setInternalActiveKey],
    );

    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent, key: string) => {
        if (disabled) return;

        const enabledItems = items.filter((item) => !item.disabled);
        const currentIndex = enabledItems.findIndex((item) => item.key === key);

        switch (e.key) {
          case 'Enter':
          case ' ':
            e.preventDefault();
            handleTabClick(key, items.find((i) => i.key === key)!);
            break;
          case 'ArrowLeft':
          case 'ArrowRight':
            if (orientation === 'horizontal') {
              e.preventDefault();
              const direction = e.key === 'ArrowRight' ? 1 : -1;
              const nextIndex = (currentIndex + direction + enabledItems.length) % enabledItems.length;
              const nextTab = tabRefs.current.get(enabledItems[nextIndex]?.key);
              nextTab?.focus();
            }
            break;
          case 'ArrowUp':
          case 'ArrowDown':
            if (orientation === 'vertical') {
              e.preventDefault();
              const direction = e.key === 'ArrowDown' ? 1 : -1;
              const nextIndex = (currentIndex + direction + enabledItems.length) % enabledItems.length;
              const nextTab = tabRefs.current.get(enabledItems[nextIndex]?.key);
              nextTab?.focus();
            }
            break;
          case 'Home':
            e.preventDefault();
            const firstTab = tabRefs.current.get(enabledItems[0]?.key);
            firstTab?.focus();
            break;
          case 'End':
            e.preventDefault();
            const lastTab = tabRefs.current.get(enabledItems[enabledItems.length - 1]?.key);
            lastTab?.focus();
            break;
          default:
            break;
        }
      },
      [disabled, items, orientation, handleTabClick],
    );

    const isVertical = placement === 'left' || placement === 'right';
    const placementClass = `tabList${placement.charAt(0).toUpperCase() + placement.slice(1)}`;
    const indicatorPlacementClass = `indicator${placement.charAt(0).toUpperCase() + placement.slice(1)}`;

    const renderChildren = () => {
      if (children) {
        return React.Children.map(children, (child) => {
          if (React.isValidElement<TabPaneProps>(child)) {
            const isActive = child.props.tabKey === internalActiveKey;
            return React.cloneElement(child, {
              className: cn(child.props.className, isActive && styles.active),
            });
          }
          return child;
        });
      }
      return null;
    };

    return (
      <div
        ref={ref}
        className={cn(
          styles.wrapper,
          isVertical ? styles.vertical : styles.horizontal,
          variant !== 'line' && styles[variant],
          className,
        )}
        style={style}
      >
        <div
          id={tabListId}
          role="tablist"
          aria-orientation={orientation}
          className={cn(styles.tabList, styles[placementClass])}
        >
          {items.map((item) => {
            const isActive = item.key === internalActiveKey;
            const rovingProps = getItemProps(item.key);

            return (
              <button
                key={item.key}
                ref={(el) => {
                  if (el) tabRefs.current.set(item.key, el);
                  else tabRefs.current.delete(item.key);
                }}
                id={`tab-${item.key}`}
                role="tab"
                aria-selected={isActive}
                aria-controls={`tabpanel-${item.key}`}
                aria-disabled={disabled || item.disabled}
                tabIndex={isActive ? 0 : -1}
                className={cn(
                  styles.tab,
                  styles[size],
                  isActive && styles.active,
                  (disabled || item.disabled) && styles.disabled,
                )}
                onClick={() => handleTabClick(item.key, item)}
                onKeyDown={(e) => handleKeyDown(e, item.key)}
                {...rovingProps}
              >
                {item.label}
              </button>
            );
          })}
          {variant === 'line' && (
            <div className={cn(styles.indicator, styles[indicatorPlacementClass])} style={indicatorStyle} />
          )}
        </div>

        <div className={styles.tabPanels} id={`${tabsId}-panels`}>
          {renderChildren()}
        </div>
      </div>
    );
  },
);

Tabs.displayName = 'Tabs';

export { TabPane };
export type { TabsProps, TabPaneProps, TabItem };
