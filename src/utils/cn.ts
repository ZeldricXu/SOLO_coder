import { clsx, type ClassValue } from 'clsx';

export const cn = (...inputs: ClassValue[]): string => {
  return clsx(inputs);
};
