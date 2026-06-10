import { useState, useEffect, useCallback } from 'react';
import { wasm } from '../wasm';
import type { WASMBindings } from '../types';

interface UseWasmResult {
  isLoading: boolean;
  error: Error | null;
  wasm: WASMBindings;
}

export function useWasm(): UseWasmResult {
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    let mounted = true;

    const initWasm = async () => {
      try {
        setIsLoading(true);
        await wasm.init();
        if (mounted) {
          setIsLoading(false);
        }
      } catch (err) {
        if (mounted) {
          setError(err instanceof Error ? err : new Error('Failed to initialize WASM'));
          setIsLoading(false);
        }
      }
    };

    initWasm();

    return () => {
      mounted = false;
    };
  }, []);

  const memoizedWasm = useCallback(() => wasm, [])();

  return {
    isLoading,
    error,
    wasm: memoizedWasm,
  };
}
