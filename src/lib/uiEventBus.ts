export type UINotificationType = 'success' | 'warning' | 'error' | 'info';

export interface UINotification {
  id: string;
  type: UINotificationType;
  message: string;
  timeout?: number;
  createdAt: number;
}

export type UIEventType =
  | { name: 'notification:add'; payload: UINotification }
  | { name: 'notification:remove'; payload: string }
  | { name: 'notification:clear'; payload?: undefined }
  | { name: 'modal:open'; payload: { id: string; data?: unknown } }
  | { name: 'modal:close'; payload: string }
  | { name: 'theme:change'; payload: string }
  | { name: 'saving:start'; payload?: { silent?: boolean } }
  | { name: 'saving:success'; payload?: undefined }
  | { name: 'saving:error'; payload: string };

type UIEventHandler = (payload: any) => void;

export class UIEventBus {
  private handlers: Map<string, Set<UIEventHandler>> = new Map();
  private static instance: UIEventBus;

  static getInstance(): UIEventBus {
    if (!UIEventBus.instance) {
      UIEventBus.instance = new UIEventBus();
    }
    return UIEventBus.instance;
  }

  on(eventName: UIEventType['name'], handler: UIEventHandler): () => void {
    if (!this.handlers.has(eventName)) {
      this.handlers.set(eventName, new Set());
    }
    this.handlers.get(eventName)!.add(handler);
    return () => this.off(eventName, handler);
  }

  off(eventName: UIEventType['name'], handler: UIEventHandler): void {
    this.handlers.get(eventName)?.delete(handler);
  }

  emit<T extends UIEventType>(event: T): void {
    const handlers = this.handlers.get(event.name);
    if (!handlers || handlers.size === 0) return;
    handlers.forEach((h) => {
      try {
        h((event as any).payload);
      } catch (e) {
        console.error(`[UIEventBus] handler error for ${event.name}:`, e);
      }
    });
  }

  clear(): void {
    this.handlers.clear();
  }

  listenerCount(eventName: UIEventType['name']): number {
    return this.handlers.get(eventName)?.size || 0;
  }
}

export const uiEventBus = UIEventBus.getInstance();
