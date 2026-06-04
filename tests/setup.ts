import '@testing-library/jest-dom';
import { vi } from 'vitest';

vi.mock('electron', () => ({
  ipcRenderer: {
    invoke: vi.fn(),
    send: vi.fn(),
    on: vi.fn(),
    once: vi.fn(),
    removeListener: vi.fn(),
    removeAllListeners: vi.fn(),
  },
  contextBridge: {
    exposeInMainWorld: vi.fn(),
  },
}));

Object.defineProperty(window, 'electron', {
  value: {
    ipc: {
      invoke: vi.fn().mockResolvedValue({ success: true, data: null }),
      send: vi.fn(),
      on: vi.fn().mockReturnValue(() => {}),
      once: vi.fn(),
      removeListener: vi.fn(),
      removeAllListeners: vi.fn(),
    },
    platform: 'darwin',
    versions: {
      node: '20.0.0',
      chrome: '120.0.0',
      electron: '28.0.0',
    },
  },
  writable: true,
});

vi.mock('nodejieba', () => ({
  cut: vi.fn((text: string) => text.split(/[\s,，。.！!？?]/).filter(Boolean)),
  cutForSearch: vi.fn((text: string) => text.split(/[\s,，。.！!？?]/).filter(Boolean)),
}));

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

Object.defineProperty(window, 'ResizeObserver', {
  writable: true,
  value: ResizeObserverMock,
});
