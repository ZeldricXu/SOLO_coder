import { CoreEntity, RunInstance } from '../types';

export interface ProcessingContext {
  traceId: string;
  startTime: number;
  entity?: CoreEntity;
  config?: Record<string, unknown>;
  metadata: Record<string, unknown>;
}

export interface ProcessingResult<T = unknown> {
  success: boolean;
  data?: T;
  error?: string;
  metrics?: Record<string, number>;
}

export interface Event {
  id: string;
  type: string;
  source: string;
  data: Record<string, unknown>;
  timestamp: string;
  traceId?: string;
}

export interface EventHandler {
  (event: Event, context: ProcessingContext): Promise<void>;
}

export interface CoreEngineConfig {
  processingTimeout: number;
  maxRetries: number;
  retryDelay: number;
  enableCompensation: boolean;
  eventBusSize: number;
}

export interface Resource {
  id: string;
  type: string;
  config: Record<string, unknown>;
  labels: Record<string, string>;
  status: 'provisioning' | 'running' | 'stopped' | 'failed';
  created_at: string;
  updated_at: string;
}
