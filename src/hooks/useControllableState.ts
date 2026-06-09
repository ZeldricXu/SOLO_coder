import { useState, useCallback, useEffect, useRef } from 'react';

export function useControllableState<T>(
  controlledValue: T | undefined,
  defaultValue: T | (() => T),
  onChange?: (value: T) => void,
): [T, (newValue: T | ((prev: T) => T)) => void] {
  const [uncontrolledValue, setUncontrolledValue] = useState(defaultValue);
  const isControlled = controlledValue !== undefined;
  const value = isControlled ? controlledValue : uncontrolledValue;
  const prevControlledRef = useRef(isControlled);

  useEffect(() => {
    if (prevControlledRef.current !== isControlled) {
      console.warn(
        `Warning: A component is changing from ${prevControlledRef.current ? 'controlled' : 'uncontrolled'} to ${isControlled ? 'controlled' : 'uncontrolled'}. This should not happen.`,
      );
    }
    prevControlledRef.current = isControlled;
  }, [isControlled]);

  const setValue = useCallback(
    (newValue: T | ((prev: T) => T)) => {
      if (isControlled) {
        const resolvedValue =
          typeof newValue === 'function' ? (newValue as (prev: T) => T)(value) : newValue;
        onChange?.(resolvedValue);
      } else {
        setUncontrolledValue(newValue);
        const resolvedValue =
          typeof newValue === 'function'
            ? (newValue as (prev: T) => T)(uncontrolledValue)
            : newValue;
        onChange?.(resolvedValue);
      }
    },
    [isControlled, value, uncontrolledValue, onChange],
  );

  return [value, setValue];
}
