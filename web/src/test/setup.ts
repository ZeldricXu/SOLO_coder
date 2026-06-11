import { beforeEach, vi } from 'vitest';
import '@testing-library/jest-dom';

class MockWebSocket {
  readyState = 1;
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;

  constructor(_url: string) {
    setTimeout(() => {
      this.onopen?.({} as Event);
    }, 0);
  }

  onopen: ((e: Event) => void) | null = null;
  onmessage: ((e: MessageEvent) => void) | null = null;
  onerror: ((e: Event) => void) | null = null;
  onclose: ((e: CloseEvent) => void) | null = null;

  send = vi.fn();
  close = vi.fn(() => {
    this.readyState = MockWebSocket.CLOSED;
    setTimeout(() => this.onclose?.({} as CloseEvent), 0);
  });

  addEventListener = vi.fn();
  removeEventListener = vi.fn();
  dispatchEvent = vi.fn();
}

(global as any).WebSocket = MockWebSocket;

if (!global.crypto) {
  (global as any).crypto = {};
}
(global.crypto as any).randomUUID = () => 'test-uuid-' + Math.random().toString(36).slice(2, 10);

const localStorageMock = (() => {
  let store: Record<string, string> = {};
  return {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => { store[key] = value.toString(); },
    removeItem: (key: string) => { delete store[key]; },
    clear: () => { store = {}; },
  };
})();
Object.defineProperty(global, 'localStorage', { value: localStorageMock });

beforeEach(() => {
  localStorageMock.clear();
  vi.clearAllMocks();
});
