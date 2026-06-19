import { render, screen, act, cleanup } from '@testing-library/react';
import React from 'react';
import { useEditorStore } from '@renderer/stores/editorStore';
import { createMockNote } from '../__fixtures__/testFixtures';

jest.useFakeTimers();

describe('Editor Store Memory Management', () => {
  beforeEach(() => {
    const { resetEditor, dispose } = useEditorStore.getState();
    dispose();
    resetEditor(null);
  });

  afterEach(() => {
    cleanup();
    jest.clearAllMocks();
  });

  describe('resetEditor', () => {
    it('should clear all note-related state when called with null', () => {
      const store = useEditorStore.getState();
      store.setAutocompletePos({ top: 10, left: 20 });
      store.setAutocompleteSearch('test');
      store.updateContent('some content');
      store.setIsDragging(true);

      store.resetEditor(null);

      const state = useEditorStore.getState();
      expect(state.currentNoteId).toBeNull();
      expect(state.autocompletePos).toBeNull();
      expect(state.autocompleteSearch).toBe('');
      expect(state.wikiLinkStart).toBeNull();
      expect(state.cursorPosition).toBeNull();
      expect(state.documentContent).toBe('');
      expect(state.isDragging).toBe(false);
    });

    it('should set new note id and clear transient state', () => {
      const store = useEditorStore.getState();
      store.setAutocompletePos({ top: 10, left: 20 });
      store.setAutocompleteSearch('test');

      store.resetEditor('note-123');

      const state = useEditorStore.getState();
      expect(state.currentNoteId).toBe('note-123');
      expect(state.autocompletePos).toBeNull();
      expect(state.autocompleteSearch).toBe('');
      expect(state.wikiLinkStart).toBeNull();
    });
  });

  describe('clearSubscriptions', () => {
    it('should call all registered subscription callbacks', () => {
      const store = useEditorStore.getState();
      const callback1 = jest.fn();
      const callback2 = jest.fn();

      store.addSubscription(callback1);
      store.addSubscription(callback2);

      store.clearSubscriptions();

      expect(callback1).toHaveBeenCalledTimes(1);
      expect(callback2).toHaveBeenCalledTimes(1);
    });

    it('should clear all subscriptions after calling', () => {
      const store = useEditorStore.getState();
      const callback = jest.fn();

      store.addSubscription(callback);
      store.clearSubscriptions();

      const state = useEditorStore.getState();
      expect(state._subscriptions.size).toBe(0);
    });
  });

  describe('dispose', () => {
    it('should clear subscriptions and reset all state', () => {
      const store = useEditorStore.getState();
      const callback = jest.fn();

      store.addSubscription(callback);
      store.setAutocompletePos({ top: 10, left: 20 });
      store.updateContent('test content');
      store.resetEditor('note-1');

      store.dispose();

      expect(callback).toHaveBeenCalledTimes(1);
      const state = useEditorStore.getState();
      expect(state.currentNoteId).toBeNull();
      expect(state.autocompletePos).toBeNull();
      expect(state.documentContent).toBe('');
      expect(state.isDragging).toBe(false);
      expect(state._subscriptions.size).toBe(0);
    });
  });

  describe('addSubscription', () => {
    it('should return an unsubscribe function', () => {
      const store = useEditorStore.getState();
      const callback = jest.fn();

      const unsubscribe = store.addSubscription(callback);

      expect(typeof unsubscribe).toBe('function');

      unsubscribe();

      const state = useEditorStore.getState();
      expect(state._subscriptions.size).toBe(0);
    });

    it('should add callback to subscriptions set', () => {
      const store = useEditorStore.getState();
      const callback = jest.fn();

      store.addSubscription(callback);

      const state = useEditorStore.getState();
      expect(state._subscriptions.size).toBe(1);
    });
  });
});

describe('Editor Canvas Event Listener Cleanup', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('wikilink-click event listener pattern should add and remove correctly', () => {
    const addEventListenerSpy = jest.spyOn(document, 'addEventListener');
    const removeEventListenerSpy = jest.spyOn(document, 'removeEventListener');

    const TestComponent = () => {
      React.useEffect(() => {
        const handler = () => {};
        document.addEventListener('wikilink-click', handler);
        return () => document.removeEventListener('wikilink-click', handler);
      }, []);
      return <div>Test</div>;
    };

    const { unmount } = render(<TestComponent />);

    expect(addEventListenerSpy).toHaveBeenCalledWith(
      'wikilink-click',
      expect.any(Function)
    );

    unmount();

    expect(removeEventListenerSpy).toHaveBeenCalledWith(
      'wikilink-click',
      expect.any(Function)
    );

    addEventListenerSpy.mockRestore();
    removeEventListenerSpy.mockRestore();
  });
});

describe('Save Timeout Cleanup', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllTimers();
  });

  afterEach(() => {
    jest.clearAllTimers();
  });

  it('should clear timeout on unmount pattern', () => {
    const setTimeoutSpy = jest.spyOn(global, 'setTimeout');
    const clearTimeoutSpy = jest.spyOn(global, 'clearTimeout');

    const TestComponent = () => {
      const timeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);

      const triggerSave = React.useCallback(() => {
        if (timeoutRef.current) {
          clearTimeout(timeoutRef.current);
        }
        timeoutRef.current = setTimeout(() => {}, 500);
      }, []);

      React.useEffect(() => {
        return () => {
          if (timeoutRef.current) {
            clearTimeout(timeoutRef.current);
            timeoutRef.current = null;
          }
        };
      }, []);

      return <button onClick={triggerSave}>Trigger Save</button>;
    };

    const { unmount, getByText } = render(<TestComponent />);

    act(() => {
      getByText('Trigger Save').click();
    });

    expect(setTimeoutSpy).toHaveBeenCalledTimes(1);

    const timeoutId = setTimeoutSpy.mock.results[0].value;
    expect(timeoutId).toBeDefined();

    unmount();

    expect(clearTimeoutSpy).toHaveBeenCalledWith(timeoutId);

    setTimeoutSpy.mockRestore();
    clearTimeoutSpy.mockRestore();
  });

  it('should debounce multiple saves with clearTimeout', () => {
    const setTimeoutSpy = jest.spyOn(global, 'setTimeout');
    const clearTimeoutSpy = jest.spyOn(global, 'clearTimeout');

    const TestComponent = ({ onSave }: { onSave: jest.Mock }) => {
      const timeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);

      const triggerSave = React.useCallback((content: string) => {
        if (timeoutRef.current) {
          clearTimeout(timeoutRef.current);
        }
        timeoutRef.current = setTimeout(() => {
          onSave(content);
        }, 500);
      }, [onSave]);

      return <button onClick={() => triggerSave('content')}>Save</button>;
    };

    const onSaveMock = jest.fn();
    const { getByText } = render(<TestComponent onSave={onSaveMock} />);

    act(() => {
      getByText('Save').click();
      getByText('Save').click();
      getByText('Save').click();
    });

    expect(setTimeoutSpy).toHaveBeenCalledTimes(3);
    expect(clearTimeoutSpy).toHaveBeenCalledTimes(2);

    act(() => {
      jest.advanceTimersByTime(500);
    });

    expect(onSaveMock).toHaveBeenCalledTimes(1);

    setTimeoutSpy.mockRestore();
    clearTimeoutSpy.mockRestore();
  });
});
