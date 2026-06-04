'use client';

import { useState, useEffect, useCallback } from 'react';

interface ScrollPosition {
  x: number;
  y: number;
  scrollY: number;
  scrollX: number;
  direction: 'up' | 'down' | 'left' | 'right' | null;
}

export function useScrollPosition(
  element?: HTMLElement | null,
  wait: number = 0
): ScrollPosition {
  const [position, setPosition] = useState<ScrollPosition>({
    x: 0,
    y: 0,
    scrollY: 0,
    scrollX: 0,
    direction: null,
  });

  const handleScroll = useCallback(() => {
    const target = element || document.documentElement;

    setPosition((prev) => {
      const currentX = target.scrollLeft;
      const currentY = target.scrollTop;

      let direction: ScrollPosition['direction'] = null;

      if (currentY > prev.scrollY) {
        direction = 'down';
      } else if (currentY < prev.scrollY) {
        direction = 'up';
      } else if (currentX > prev.scrollX) {
        direction = 'right';
      } else if (currentX < prev.scrollX) {
        direction = 'left';
      }

      return {
        x: currentX,
        y: currentY,
        scrollY: currentY,
        scrollX: currentX,
        direction,
      };
    });
  }, [element]);

  useEffect(() => {
    const target = element || window;

    let timeoutId: NodeJS.Timeout | null = null;

    const handleScrollWithDebounce = () => {
      if (wait > 0) {
        if (timeoutId) clearTimeout(timeoutId);
        timeoutId = setTimeout(handleScroll, wait);
      } else {
        handleScroll();
      }
    };

    target.addEventListener('scroll', handleScrollWithDebounce, {
      passive: true,
    });

    handleScroll();

    return () => {
      target.removeEventListener('scroll', handleScrollWithDebounce);
      if (timeoutId) clearTimeout(timeoutId);
    };
  }, [element, wait, handleScroll]);

  return position;
}

export function useScrollDirection(): 'up' | 'down' | null {
  const { direction, scrollY } = useScrollPosition(undefined, 100);
  const [scrollDirection, setScrollDirection] = useState<'up' | 'down' | null>(
    null
  );

  useEffect(() => {
    if (scrollY < 10) {
      setScrollDirection(null);
    } else if (direction === 'up' || direction === 'down') {
      setScrollDirection(direction);
    }
  }, [direction, scrollY]);

  return scrollDirection;
}
