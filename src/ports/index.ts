import {
  LogEntry,
  TraceSpan,
  UserPrincipal,
  APIResponse,
  MetricPoint,
  Notification,
  StoredObject,
  TopologyGraph,
  FlameGraphNode,
  ProfileSample,
  ScheduledTask,
} from '@apptypes/index';

export interface ILogger {
  debug(message: string, data?: Record<string, unknown>): void;
  info(message: string, data?: Record<string, unknown>): void;
  warn(message: string, data?: Record<string, unknown>): void;
  error(message: string, data?: Record<string, unknown>): void;
  fatal(message: string, data?: Record<string, unknown>): void;
  child(context: Record<string, unknown>): ILogger;
}

export interface IAuthService {
  authenticate(token: string): Promise<UserPrincipal | null>;
  authorize(principal: UserPrincipal, permission: string): boolean;
  generateToken(principal: UserPrincipal): Promise<string>;
  hashPassword(password: string): Promise<string>;
  verifyPassword(password: string, hash: string): Promise<boolean>;
}

export interface IRateLimiter {
  checkLimit(key: string): Promise<{ allowed: boolean; remaining: number; resetTime: number }>;
  resetLimit(key: string): Promise<void>;
}

export interface ILogPipeline {
  collect(rawLog: string | Record<string, unknown>): Promise<void>;
  processBatch(rawLogs: (string | Record<string, unknown>)[]): Promise<LogEntry[]>;
  registerFilter(name: string, filter: (log: LogEntry) => boolean): void;
  registerRouter(name: string, router: (log: LogEntry) => string[]): void;
  subscribe(destination: string, handler: (log: LogEntry) => void): void;
}

export interface IMetricsAggregator {
  ingest(point: MetricPoint): Promise<void>;
  ingestBatch(points: MetricPoint[]): Promise<void>;
  query(metricName: string, tags: Record<string, string>, startTime: number, endTime: number): Promise<MetricPoint[]>;
  getAggregated(metricName: string, tags: Record<string, string>, window: string): Promise<{
    avg: number;
    sum: number;
    min: number;
    max: number;
    count: number;
    p95: number;
    p99: number;
  }>;
  stop(): void;
}

export interface INotificationService {
  send(notification: Omit<Notification, 'id' | 'status' | 'retry_count' | 'created_at' | 'sent_at'>): Promise<Notification>;
  getStatus(id: string): Promise<Notification | null>;
  retry(id: string): Promise<Notification>;
  registerChannel(type: string, channel: INotificationChannel): void;
}

export interface INotificationChannel {
  send(recipient: string, content: Record<string, unknown>): Promise<boolean>;
  getType(): string;
}

export interface IStorageAdapter {
  put(bucket: string, key: string, data: Buffer, metadata?: Record<string, string>): Promise<StoredObject>;
  get(bucket: string, key: string): Promise<Buffer | null>;
  delete(bucket: string, key: string): Promise<boolean>;
  list(bucket: string, prefix?: string): Promise<StoredObject[]>;
  getMetadata(bucket: string, key: string): Promise<StoredObject | null>;
}

export interface IMetadataIndex {
  index(object: StoredObject): Promise<void>;
  search(query: Record<string, string>): Promise<StoredObject[]>;
  update(objectId: string, metadata: Record<string, string>): Promise<boolean>;
  delete(objectId: string): Promise<boolean>;
}

export interface ITopologyBuilder {
  ingestSpan(span: TraceSpan): Promise<void>;
  ingestSpans(spans: TraceSpan[]): Promise<void>;
  getGraph(service?: string, timeWindow?: { start: number; end: number }): Promise<TopologyGraph>;
  getDependencies(service: string): Promise<string[]>;
  getDependents(service: string): Promise<string[]>;
}

export interface IProfiler {
  startCPUProfiling(durationMs?: number): Promise<string>;
  stopCPUProfiling(profileId: string): Promise<ProfileSample[]>;
  startMemoryProfiling(durationMs?: number): Promise<string>;
  stopMemoryProfiling(profileId: string): Promise<ProfileSample[]>;
  generateFlameGraph(samples: ProfileSample[]): FlameGraphNode;
  compareFlameGraphs(graph1: FlameGraphNode, graph2: FlameGraphNode): FlameGraphNode;
}

export interface IScheduler {
  schedule(task: Omit<ScheduledTask, 'id' | 'created_at' | 'last_run' | 'next_run'>): Promise<ScheduledTask>;
  unschedule(taskId: string): Promise<boolean>;
  list(): Promise<ScheduledTask[]>;
  get(taskId: string): Promise<ScheduledTask | null>;
  update(taskId: string, updates: Partial<Omit<ScheduledTask, 'id' | 'created_at'>>): Promise<ScheduledTask | null>;
  trigger(taskId: string): Promise<void>;
  registerHandler(name: string, handler: (payload: Record<string, unknown>) => Promise<void>): void;
}

export interface IRequestContext {
  traceId: string;
  spanId: string;
  principal: UserPrincipal | null;
  startTime: number;
  attributes: Record<string, unknown>;
}

export interface IEventHandler {
  emit(event: string, data: Record<string, unknown>): void;
  on(event: string, handler: (data: Record<string, unknown>) => void): void;
  once(event: string, handler: (data: Record<string, unknown>) => void): void;
  off(event: string, handler: (data: Record<string, unknown>) => void): void;
}

export interface ICache {
  get<T>(key: string): Promise<T | null>;
  set<T>(key: string, value: T, ttlMs?: number): Promise<void>;
  delete(key: string): Promise<boolean>;
  exists(key: string): Promise<boolean>;
  incr(key: string, amount?: number): Promise<number>;
}

export interface IAPIResponseBuilder {
  success<T>(data: T, pagination?: APIResponse['pagination']): APIResponse<T>;
  error(code: number, message: string): APIResponse;
  conflict(resourceId: string): APIResponse;
}
