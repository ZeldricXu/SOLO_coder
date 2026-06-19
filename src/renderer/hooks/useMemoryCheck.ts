import { useEffect, useRef } from 'react';

interface MemoryInfo {
  usedJSHeapSize: number;
  totalJSHeapSize: number;
  jsHeapSizeLimit: number;
}

interface PerformanceWithMemory extends Performance {
  memory?: MemoryInfo;
}

export const useMemoryCheck = (noteId: string | null | undefined) => {
  const switchCountRef = useRef(0);
  const lastNoteIdRef = useRef<string | null | undefined>(null);
  const initialMemoryRef = useRef<number | null>(null);

  useEffect(() => {
    if (!import.meta.env.DEV) return;

    if (noteId !== lastNoteIdRef.current) {
      lastNoteIdRef.current = noteId;
      switchCountRef.current += 1;

      const perf = performance as PerformanceWithMemory;
      if (perf.memory && initialMemoryRef.current === null) {
        initialMemoryRef.current = perf.memory.usedJSHeapSize;
      }

      if (switchCountRef.current >= 20 && switchCountRef.current % 20 === 0) {
        if (perf.memory && initialMemoryRef.current !== null) {
          const currentMemory = perf.memory.usedJSHeapSize;
          const memoryGrowth = currentMemory - initialMemoryRef.current;
          const growthPercent = (memoryGrowth / initialMemoryRef.current) * 100;

          if (growthPercent > 50) {
            console.warn(
              `[Memory Check] After ${switchCountRef.current} note switches, memory grew by ${(memoryGrowth / 1024 / 1024).toFixed(2)}MB (${growthPercent.toFixed(1)}%). Possible memory leak.`
            );
          } else {
            console.log(
              `[Memory Check] After ${switchCountRef.current} note switches, memory grew by ${(memoryGrowth / 1024 / 1024).toFixed(2)}MB (${growthPercent.toFixed(1)}%).`
            );
          }
        } else {
          console.log(
            `[Memory Check] ${switchCountRef.current} note switches tracked. Note: performance.memory not available.`
          );
        }
      }
    }
  }, [noteId]);

  useEffect(() => {
    if (!import.meta.env.DEV) return;

    return () => {
      if (switchCountRef.current > 0) {
        console.log(
          `[Memory Check] Component unmounted after ${switchCountRef.current} note switches.`
        );
      }
    };
  }, []);
};
