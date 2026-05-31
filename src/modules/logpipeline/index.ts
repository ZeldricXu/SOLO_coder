import { EventEmitter } from 'events';
import type { ILogPipeline } from '@ports/index';
import type { LogEntry } from '@apptypes/index';
import { rootLogger } from '@modules/logging';
import {
  generateId,
  nowISO,
  chunkArray,
  safeJsonParse,
} from '@utils/index';
import { z } from 'zod';

const LogEntrySchema = z.object({
  timestamp: z.string(),
  level: z.enum(['debug', 'info', 'warn', 'error', 'fatal']),
  message: z.string(),
  service: z.string(),
  trace_id: z.string(),
  span_id: z.string(),
  attributes: z.record(z.unknown()),
});

const LOG_LEVELS: string[] = ['debug', 'info', 'warn', 'error', 'fatal'];
const DEFAULT_BATCH_SIZE = 100;
const DEFAULT_MAX_LOG_SIZE = 10 * 1024 * 1024;

type LogFilter = (log: LogEntry) => boolean;
type LogRouter = (log: LogEntry) => string[];
type LogHandler = (log: LogEntry) => void;

export class LogPipeline implements ILogPipeline {
  private readonly logger = rootLogger.child({ module: 'LogPipeline' });
  private readonly filters = new Map<string, LogFilter>();
  private readonly routers = new Map<string, LogRouter>();
  private readonly subscribers = new Map<string, LogHandler[]>();
  private readonly eventEmitter = new EventEmitter();
  private batchSize = DEFAULT_BATCH_SIZE;
  private maxLogSize = DEFAULT_MAX_LOG_SIZE;

  constructor() {
    this.registerBuiltinFilters();
    this.registerBuiltinRouters();
  }

  registerFilter(name: string, filter: LogFilter): void {
    this.filters.set(name, filter);
    this.logger.info('Filter registered', { filter_name: name });
  }

  registerRouter(name: string, router: LogRouter): void {
    this.routers.set(name, router);
    this.logger.info('Router registered', { router_name: name });
  }

  subscribe(destination: string, handler: LogHandler): void {
    if (!this.subscribers.has(destination)) {
      this.subscribers.set(destination, []);
    }
    this.subscribers.get(destination)!.push(handler);
    this.logger.info('Subscriber added', { destination });
  }

  on(destination: string, handler: LogHandler): void {
    this.eventEmitter.on(destination, handler);
  }

  off(destination: string, handler: LogHandler): void {
    this.eventEmitter.off(destination, handler);
  }

  getFilterNames(): string[] {
    return Array.from(this.filters.keys());
  }

  getRouterNames(): string[] {
    return Array.from(this.routers.keys());
  }

  getDestinations(): string[] {
    return Array.from(this.subscribers.keys());
  }

  unregisterFilter(name: string): boolean {
    return this.filters.delete(name);
  }

  unregisterRouter(name: string): boolean {
    return this.routers.delete(name);
  }

  setBatchSize(size: number): void {
    this.batchSize = Math.max(1, size);
  }

  setMaxLogSize(size: number): void {
    this.maxLogSize = Math.max(1024, size);
  }

  async collect(rawLog: string | Record<string, unknown>): Promise<void> {
    const log = this.parseLog(rawLog);
    if (!log) return;

    if (!this.runFilters(log)) return;

    const destinations = this.runRouters(log);
    this.dispatch(log, destinations);

    this.logger.debug('Log processed', {
      trace_id: log.trace_id,
      destinations,
    });
  }

  async processBatch(
    rawLogs: (string | Record<string, unknown>)[],
  ): Promise<LogEntry[]> {
    const processedLogs: LogEntry[] = [];
    const batches = chunkArray(rawLogs, this.batchSize);

    for (const batch of batches) {
      const results = await Promise.allSettled(
        batch.map((raw) => this.processSingle(raw)),
      );

      for (const result of results) {
        if (result.status === 'fulfilled' && result.value) {
          processedLogs.push(result.value);
        }
      }
    }

    this.logger.info('Batch processing completed', {
      received: rawLogs.length,
      processed: processedLogs.length,
    });

    return processedLogs;
  }

  private registerBuiltinFilters(): void {
    this.registerFilter('level-filter', this.createLevelFilter());
    this.registerFilter('size-filter', this.createSizeFilter());
    this.registerFilter('required-fields', this.createValidationFilter());
  }

  private registerBuiltinRouters(): void {
    this.registerRouter('level-router', this.createLevelRouter());
    this.registerRouter('service-router', this.createServiceRouter());
    this.registerRouter('trace-router', this.createTraceRouter());
  }

  private createLevelFilter(): LogFilter {
    return (log) => {
      const minLevelIndex = LOG_LEVELS.indexOf('debug');
      const logLevelIndex = LOG_LEVELS.indexOf(log.level);
      return logLevelIndex >= minLevelIndex;
    };
  }

  private createSizeFilter(): LogFilter {
    return (log) => {
      const logSize = this.estimateLogSize(log);
      if (logSize > this.maxLogSize) {
        this.logger.warn('Log entry too large, dropped', {
          size: logSize,
          max_size: this.maxLogSize,
          service: log.service,
        });
        return false;
      }
      return true;
    };
  }

  private createValidationFilter(): LogFilter {
    return (log) => {
      const result = LogEntrySchema.safeParse(log);
      if (!result.success) {
        this.logger.warn('Log entry validation failed', {
          errors: result.error.issues,
        });
        return false;
      }
      return true;
    };
  }

  private createLevelRouter(): LogRouter {
    return (log) => {
      const destinations = [`level.${log.level}`];
      if (log.level === 'error' || log.level === 'fatal') {
        destinations.push('alerts');
      }
      return destinations;
    };
  }

  private createServiceRouter(): LogRouter {
    return (log) => [`service.${log.service}`];
  }

  private createTraceRouter(): LogRouter {
    return (log) => [`trace.${log.trace_id}`];
  }

  private parseLog(
    rawLog: string | Record<string, unknown>,
  ): LogEntry | null {
    const logData = this.normalizeInput(rawLog);
    const log = this.buildLogEntry(logData);

    const result = LogEntrySchema.safeParse(log);
    if (!result.success) {
      this.logger.warn('Failed to parse log entry', {
        input: this.truncateInput(rawLog),
        errors: result.error.issues,
      });
      return null;
    }

    return log;
  }

  private normalizeInput(
    rawLog: string | Record<string, unknown>,
  ): Record<string, unknown> {
    if (typeof rawLog === 'string') {
      return safeJsonParse(rawLog, {} as Record<string, unknown>);
    }
    return rawLog;
  }

  private buildLogEntry(logData: Record<string, unknown>): LogEntry {
    return {
      timestamp: (logData.timestamp as string) || nowISO(),
      level: (logData.level as string) || 'info',
      message: (logData.message as string) || '',
      service: (logData.service as string) || 'unknown',
      trace_id: (logData.trace_id as string) || generateId('trace_'),
      span_id: (logData.span_id as string) || generateId('span_'),
      attributes: (logData.attributes as Record<string, unknown>) || {},
    };
  }

  private truncateInput(
    rawLog: string | Record<string, unknown>,
  ): string | Record<string, unknown> {
    if (typeof rawLog === 'string') {
      return rawLog.substring(0, 200);
    }
    return rawLog;
  }

  private estimateLogSize(log: LogEntry): number {
    return (
      log.message.length +
      log.service.length +
      log.trace_id.length +
      log.span_id.length +
      JSON.stringify(log.attributes).length
    );
  }

  private runFilters(log: LogEntry): boolean {
    for (const [name, filter] of this.filters) {
      try {
        if (!filter(log)) {
          this.logger.debug('Log filtered out', {
            filter: name,
            trace_id: log.trace_id,
          });
          return false;
        }
      } catch (error) {
        this.handleFilterError(name, error);
      }
    }
    return true;
  }

  private handleFilterError(name: string, error: unknown): void {
    this.logger.error('Filter execution failed', {
      filter: name,
      error: (error as Error).message,
    });
  }

  private runRouters(log: LogEntry): string[] {
    const destinations = new Set<string>();

    for (const [name, router] of this.routers) {
      try {
        const results = router(log);
        for (const dest of results) {
          destinations.add(dest);
        }
      } catch (error) {
        this.handleRouterError(name, error);
      }
    }

    return Array.from(destinations);
  }

  private handleRouterError(name: string, error: unknown): void {
    this.logger.error('Router execution failed', {
      router: name,
      error: (error as Error).message,
    });
  }

  private dispatch(log: LogEntry, destinations: string[]): void {
    for (const destination of destinations) {
      const handlers = this.subscribers.get(destination) || [];
      for (const handler of handlers) {
        this.safeInvokeHandler(handler, log, destination);
      }
      this.eventEmitter.emit(destination, log);
    }
  }

  private safeInvokeHandler(
    handler: LogHandler,
    log: LogEntry,
    destination: string,
  ): void {
    try {
      setImmediate(() => handler(log));
    } catch (error) {
      this.logger.error('Handler execution failed', {
        destination,
        error: (error as Error).message,
      });
    }
  }

  private async processSingle(
    rawLog: string | Record<string, unknown>,
  ): Promise<LogEntry | null> {
    const log = this.parseLog(rawLog);
    if (!log) return null;
    if (!this.runFilters(log)) return null;

    const destinations = this.runRouters(log);
    this.dispatch(log, destinations);

    return log;
  }
}

export const logPipeline = new LogPipeline();
