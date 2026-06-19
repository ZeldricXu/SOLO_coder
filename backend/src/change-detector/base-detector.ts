export interface ChangeEvent {
  dataSourceId: string;
  tableName: string;
  operation: 'INSERT' | 'UPDATE' | 'DELETE';
  pk?: Record<string, any>;
  beforeData?: Record<string, any>;
  afterData?: Record<string, any>;
  timestamp: Date;
}

export abstract class BaseChangeDetector {
  protected isRunning = false;
  protected listeners: Set<(event: ChangeEvent) => void> = new Set();

  abstract start(): Promise<void>;
  abstract stop(): Promise<void>;

  onEvent(listener: (event: ChangeEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  protected emit(event: ChangeEvent) {
    for (const listener of this.listeners) {
      try {
        listener(event);
      } catch {
        // swallow listener errors
      }
    }
  }
}
