import type { ComponentSize } from '@types';

export const getSizeClasses = (size: ComponentSize): Record<string, string> => {
  const sizeMap: Record<ComponentSize, { height: string; padding: string; fontSize: string; gap: string }> = {
    sm: {
      height: '32px',
      padding: '0 12px',
      fontSize: 'var(--font-size-sm)',
      gap: 'var(--spacing-xs)',
    },
    md: {
      height: '40px',
      padding: '0 16px',
      fontSize: 'var(--font-size-md)',
      gap: 'var(--spacing-sm)',
    },
    lg: {
      height: '48px',
      padding: '0 24px',
      fontSize: 'var(--font-size-lg)',
      gap: 'var(--spacing-md)',
    },
  };

  return sizeMap[size];
};

export const getIconSize = (size: ComponentSize): number => {
  const iconSizeMap: Record<ComponentSize, number> = {
    sm: 14,
    md: 16,
    lg: 20,
  };

  return iconSizeMap[size];
};

export const isValidSize = (size: unknown): size is ComponentSize => {
  return typeof size === 'string' && ['sm', 'md', 'lg'].includes(size);
};

export const composeEventHandlers = <E>(
  ...handlers: Array<((event: E) => void) | undefined>
) => {
  return (event: E) => {
    for (const handler of handlers) {
      if (typeof handler === 'function') {
        handler(event);
      }
    }
  };
};

export const createEvent = <T extends Event>(type: string, target: EventTarget): T => {
  const event = new Event(type, { bubbles: true, cancelable: true }) as T;
  Object.defineProperty(event, 'target', { writable: false, value: target });
  return event;
};
