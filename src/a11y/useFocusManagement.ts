import { useEffect, useRef, useCallback } from 'react';

export const useFocusTrap = (
  isActive: boolean,
  initialFocusRef?: React.RefObject<HTMLElement>,
) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!isActive) return;

    previousFocusRef.current = document.activeElement as HTMLElement;

    const container = containerRef.current;
    if (!container) return;

    const focusableElements = container.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    );

    if (focusableElements.length === 0) {
      container.focus();
      return;
    }

    const firstElement = focusableElements[0] as HTMLElement;
    const lastElement = focusableElements[focusableElements.length - 1] as HTMLElement;

    if (initialFocusRef?.current) {
      initialFocusRef.current.focus();
    } else {
      firstElement.focus();
    }

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return;

      if (e.shiftKey) {
        if (document.activeElement === firstElement) {
          e.preventDefault();
          lastElement.focus();
        }
      } else {
        if (document.activeElement === lastElement) {
          e.preventDefault();
          firstElement.focus();
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      if (previousFocusRef.current) {
        previousFocusRef.current.focus();
      }
    };
  }, [isActive, initialFocusRef]);

  return containerRef;
};

export const useKeyboardNavigation = <T extends HTMLElement>(
  items: Array<{ id: string; disabled?: boolean }>,
  orientation: 'horizontal' | 'vertical' = 'vertical',
) => {
  const [activeIndex, setActiveIndex] = useState<number>(0);
  const containerRef = useRef<T>(null);

  const getNextIndex = useCallback(
    (currentIndex: number, direction: 'next' | 'prev'): number => {
      const step = direction === 'next' ? 1 : -1;
      let nextIndex = currentIndex;

      do {
        nextIndex = (nextIndex + step + items.length) % items.length;
      } while (items[nextIndex]?.disabled && nextIndex !== currentIndex);

      return nextIndex;
    },
    [items],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<T>) => {
      const nextKey = orientation === 'vertical' ? 'ArrowDown' : 'ArrowRight';
      const prevKey = orientation === 'vertical' ? 'ArrowUp' : 'ArrowLeft';

      switch (e.key) {
        case nextKey:
          e.preventDefault();
          setActiveIndex((prev) => getNextIndex(prev, 'next'));
          break;
        case prevKey:
          e.preventDefault();
          setActiveIndex((prev) => getNextIndex(prev, 'prev'));
          break;
        case 'Home':
          e.preventDefault();
          setActiveIndex(0);
          break;
        case 'End':
          e.preventDefault();
          setActiveIndex(items.length - 1);
          break;
        default:
          break;
      }
    },
    [orientation, getNextIndex, items.length],
  );

  const getItemProps = useCallback(
    (index: number) => ({
      tabIndex: index === activeIndex ? 0 : -1,
      'aria-selected': index === activeIndex,
    }),
    [activeIndex],
  );

  return {
    containerRef,
    activeIndex,
    setActiveIndex,
    handleKeyDown,
    getItemProps,
  };
};

import { useState } from 'react';

export const useRovingFocus = <T extends HTMLElement>(
  itemIds: string[],
  orientation: 'horizontal' | 'vertical' = 'horizontal',
) => {
  const [focusedId, setFocusedId] = useState<string | null>(null);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<T>, currentId: string) => {
      const currentIndex = itemIds.indexOf(currentId);
      if (currentIndex === -1) return;

      const nextKey = orientation === 'vertical' ? 'ArrowDown' : 'ArrowRight';
      const prevKey = orientation === 'vertical' ? 'ArrowUp' : 'ArrowLeft';

      switch (e.key) {
        case nextKey:
          e.preventDefault();
          {
            const nextIndex = (currentIndex + 1) % itemIds.length;
            setFocusedId(itemIds[nextIndex] ?? null);
          }
          break;
        case prevKey:
          e.preventDefault();
          {
            const prevIndex = (currentIndex - 1 + itemIds.length) % itemIds.length;
            setFocusedId(itemIds[prevIndex] ?? null);
          }
          break;
        default:
          break;
      }
    },
    [itemIds, orientation],
  );

  const getItemProps = useCallback(
    (id: string) => ({
      tabIndex: focusedId === id ? 0 : -1,
      onKeyDown: (e: React.KeyboardEvent<T>) => handleKeyDown(e, id),
      onFocus: () => setFocusedId(id),
    }),
    [focusedId, handleKeyDown],
  );

  return {
    focusedId,
    setFocusedId,
    getItemProps,
  };
};

export const useEscapeKey = (callback: () => void, isActive: boolean = true) => {
  useEffect(() => {
    if (!isActive) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        callback();
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [callback, isActive]);
};
