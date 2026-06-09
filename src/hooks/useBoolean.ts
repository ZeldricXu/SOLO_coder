import { useState, useCallback } from 'react';

export interface UseBooleanReturn {
  value: boolean;
  setTrue: () => void;
  setFalse: () => void;
  toggle: () => void;
  set: (value: boolean) => void;
}

export const useBoolean = (initialValue: boolean = false): UseBooleanReturn => {
  const [value, setValue] = useState(initialValue);

  const setTrue = useCallback(() => setValue(true), []);
  const setFalse = useCallback(() => setValue(false), []);
  const toggle = useCallback(() => setValue((prev) => !prev), []);
  const set = useCallback((v: boolean) => setValue(v), []);

  return { value, setTrue, setFalse, toggle, set };
};
