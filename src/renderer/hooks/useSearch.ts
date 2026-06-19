import { useState, useEffect, useRef, useCallback } from 'react';
import type { Note, SearchResult, SearchOptions } from '@shared/types';

interface SearchWorkerMessage {
  type: string;
  results?: SearchResult[];
  requestId?: string;
}

export function useSearch(notes: Note[], createWorker?: () => Worker) {
  const [results, setResults] = useState<SearchResult[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const workerRef = useRef<Worker | null>(null);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const requestIdRef = useRef(0);
  const pendingRequestRef = useRef<string | null>(null);

  const initWorker = useCallback(() => {
    if (workerRef.current) return;

    const worker = createWorker 
      ? createWorker() 
      : new Worker(new URL('../workers/searchWorker.ts', import.meta.url), { type: 'module' });
    
    worker.onmessage = (e: MessageEvent<SearchWorkerMessage>) => {
      const { type, results: workerResults, requestId } = e.data;
      
      if (type === 'searchResults' && requestId === pendingRequestRef.current) {
        setResults(workerResults || []);
        setIsLoading(false);
        pendingRequestRef.current = null;
      }
    };

    workerRef.current = worker;

    if (notes.length > 0) {
      worker.postMessage({ type: 'init', notes });
    }
  }, [notes, createWorker]);

  const terminateWorker = useCallback(() => {
    if (workerRef.current) {
      workerRef.current.terminate();
      workerRef.current = null;
    }
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    pendingRequestRef.current = null;
  }, []);

  useEffect(() => {
    initWorker();
    return () => terminateWorker();
  }, [initWorker, terminateWorker]);

  useEffect(() => {
    if (workerRef.current && notes.length > 0) {
      workerRef.current.postMessage({ type: 'init', notes });
    }
  }, [notes]);

  const search = useCallback((query: string, options?: SearchOptions) => {
    if (!workerRef.current) {
      initWorker();
    }

    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }

    if (!query.trim()) {
      setResults([]);
      setIsLoading(false);
      pendingRequestRef.current = null;
      return;
    }

    setIsLoading(true);

    debounceTimerRef.current = setTimeout(() => {
      if (!workerRef.current) return;
      
      const requestId = String(++requestIdRef.current);
      pendingRequestRef.current = requestId;
      
      workerRef.current.postMessage({
        type: 'search',
        query,
        options,
        requestId,
      });
    }, 300);
  }, [initWorker]);

  const addNote = useCallback((note: Note) => {
    if (workerRef.current) {
      workerRef.current.postMessage({ type: 'addNote', note });
    }
  }, []);

  const updateNote = useCallback((note: Note) => {
    if (workerRef.current) {
      workerRef.current.postMessage({ type: 'updateNote', note });
    }
  }, []);

  const removeNote = useCallback((id: string) => {
    if (workerRef.current) {
      workerRef.current.postMessage({ type: 'removeNote', id });
    }
  }, []);

  return {
    results,
    isLoading,
    search,
    addNote,
    updateNote,
    removeNote,
  };
}
