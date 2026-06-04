import { useEffect, useRef } from 'react';
import { useAppStore } from '../stores/appStore';

export function useAutoSave(content: string | null, enabled: boolean, interval: number) {
  const saveCurrentDocument = useAppStore((state) => state.saveCurrentDocument);
  const currentDocument = useAppStore((state) => state.currentDocument);
  const timerRef = useRef<NodeJS.Timeout | null>(null);
  const lastContentRef = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled || !content || !currentDocument) {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
      return;
    }

    if (timerRef.current) {
      clearInterval(timerRef.current);
    }

    timerRef.current = setInterval(() => {
      if (content !== lastContentRef.current && content !== currentDocument.content) {
        saveCurrentDocument(content);
        lastContentRef.current = content;
      }
    }, interval);

    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [content, enabled, interval, currentDocument, saveCurrentDocument]);

  const saveNow = () => {
    if (content && currentDocument && content !== currentDocument.content) {
      saveCurrentDocument(content);
      lastContentRef.current = content;
    }
  };

  return { saveNow };
}
