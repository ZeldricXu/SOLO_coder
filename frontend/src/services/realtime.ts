import { io, Socket } from 'socket.io-client';

export interface DashboardUpdateData {
  dashboardId: string;
  widgetId?: string;
  data: Record<string, unknown>;
  timestamp: string;
  type: 'full' | 'incremental';
}

export interface MetricUpdateData {
  metricId: string;
  data: Record<string, unknown>[];
  timestamp: string;
}

export interface AlertTriggerData {
  ruleId: string;
  recordId: string;
  message: string;
  value: number;
  timestamp: string;
}

export interface FilterUpdateData {
  dashboardId: string;
  widgetId: string;
  filters: Record<string, unknown>;
  linkedWidgetIds?: string[];
  updatedBy: string;
  timestamp: string;
}

export type RealtimeEventHandler<T = unknown> = (data: T) => void;

export type UnsubscribeFn = () => void;

interface ThrottledEmitter {
  lastEmitTime: number;
  pendingData: unknown | null;
  timeoutId: ReturnType<typeof setTimeout> | null;
}

const DEFAULT_THROTTLE_MS = 500;

let socket: Socket | null = null;
const subscribedDashboards = new Set<string>();
const subscribedMetrics = new Set<string>();
const throttledEmitters = new Map<string, ThrottledEmitter>();
const eventHandlers = new Map<string, Set<RealtimeEventHandler>>();

export const realtimeService = {
  connect(token: string): Promise<void> {
    return new Promise((resolve, reject) => {
      if (socket?.connected) {
        resolve();
        return;
      }

      socket = io('/', {
        auth: { token },
        transports: ['websocket', 'polling'],
        reconnection: true,
        reconnectionAttempts: 5,
        reconnectionDelay: 1000,
        reconnectionDelayMax: 5000,
      });

      socket.on('connect', () => {
        console.log('[Realtime] connected');
        subscribedDashboards.forEach((dashboardId) => {
          socket?.emit('subscribe:dashboard', { dashboardId });
        });
        subscribedMetrics.forEach((metricId) => {
          socket?.emit('subscribe:metric', { metricId });
        });
        resolve();
      });

      socket.on('disconnect', (reason) => {
        console.log('[Realtime] disconnected:', reason);
      });

      socket.on('connect_error', (err) => {
        console.error('[Realtime] connection error:', err.message);
        reject(err);
      });

      socket.on('reconnect', (attemptNumber) => {
        console.log('[Realtime] reconnected after', attemptNumber, 'attempts');
      });

      socket.on('reconnect_attempt', (attemptNumber) => {
        console.log('[Realtime] reconnection attempt:', attemptNumber);
      });

      socket.on('reconnect_failed', () => {
        console.error('[Realtime] reconnection failed');
      });

      socket.on('dashboard:update', (data: DashboardUpdateData) => {
        this.handleEvent('dashboard:update', data);
      });

      socket.on('metric:update', (data: MetricUpdateData) => {
        this.handleEvent('metric:update', data);
      });

      socket.on('alert:trigger', (data: AlertTriggerData) => {
        this.handleEvent('alert:trigger', data);
      });

      socket.on('filter:update', (data: FilterUpdateData) => {
        this.handleEvent('filter:update', data);
      });
    });
  },

  disconnect(): void {
    if (socket) {
      socket.disconnect();
      socket = null;
    }
    subscribedDashboards.clear();
    subscribedMetrics.clear();
    eventHandlers.clear();
    throttledEmitters.forEach((emitter) => {
      if (emitter.timeoutId) {
        clearTimeout(emitter.timeoutId);
      }
    });
    throttledEmitters.clear();
  },

  isConnected(): boolean {
    return socket?.connected ?? false;
  },

  subscribeDashboard(dashboardId: string): void {
    if (subscribedDashboards.has(dashboardId)) return;
    subscribedDashboards.add(dashboardId);
    socket?.emit('subscribe:dashboard', { dashboardId });
    console.log('[Realtime] subscribed to dashboard:', dashboardId);
  },

  unsubscribeDashboard(dashboardId: string): void {
    if (!subscribedDashboards.has(dashboardId)) return;
    subscribedDashboards.delete(dashboardId);
    socket?.emit('unsubscribe:dashboard', { dashboardId });
    console.log('[Realtime] unsubscribed from dashboard:', dashboardId);
  },

  subscribeMetric(metricId: string): void {
    if (subscribedMetrics.has(metricId)) return;
    subscribedMetrics.add(metricId);
    socket?.emit('subscribe:metric', { metricId });
    console.log('[Realtime] subscribed to metric:', metricId);
  },

  unsubscribeMetric(metricId: string): void {
    if (!subscribedMetrics.has(metricId)) return;
    subscribedMetrics.delete(metricId);
    socket?.emit('unsubscribe:metric', { metricId });
    console.log('[Realtime] unsubscribed from metric:', metricId);
  },

  onDashboardUpdate(callback: RealtimeEventHandler<DashboardUpdateData>): UnsubscribeFn {
    return this.on('dashboard:update', callback);
  },

  onMetricUpdate(callback: RealtimeEventHandler<MetricUpdateData>): UnsubscribeFn {
    return this.on('metric:update', callback);
  },

  onAlertTrigger(callback: RealtimeEventHandler<AlertTriggerData>): UnsubscribeFn {
    return this.on('alert:trigger', callback);
  },

  onFilterUpdate(callback: RealtimeEventHandler<FilterUpdateData>): UnsubscribeFn {
    return this.on('filter:update', callback);
  },

  on<T = unknown>(event: string, callback: RealtimeEventHandler<T>): UnsubscribeFn {
    if (!eventHandlers.has(event)) {
      eventHandlers.set(event, new Set());
    }
    eventHandlers.get(event)!.add(callback as RealtimeEventHandler);

    return () => {
      const handlers = eventHandlers.get(event);
      if (handlers) {
        handlers.delete(callback as RealtimeEventHandler);
        if (handlers.size === 0) {
          eventHandlers.delete(event);
        }
      }
    };
  },

  handleEvent(event: string, data: unknown): void {
    const handlers = eventHandlers.get(event);
    if (handlers) {
      handlers.forEach((handler) => {
        try {
          handler(data);
        } catch (err) {
          console.error(`[Realtime] Error handling event ${event}:`, err);
        }
      });
    }
  },

  emitFilterUpdate(
    dashboardId: string,
    widgetId: string,
    filters: Record<string, unknown>,
    linkedWidgetIds?: string[],
    throttleMs: number = DEFAULT_THROTTLE_MS,
  ): void {
    const key = `filter:${dashboardId}:${widgetId}`;
    const data = {
      dashboardId,
      widgetId,
      filters,
      linkedWidgetIds,
    };

    this.throttledEmit(key, 'filter:update', data, throttleMs);
  },

  throttledEmit(
    key: string,
    event: string,
    data: unknown,
    throttleMs: number = DEFAULT_THROTTLE_MS,
  ): void {
    const now = Date.now();
    let emitter = throttledEmitters.get(key);

    if (!emitter) {
      emitter = {
        lastEmitTime: 0,
        pendingData: null,
        timeoutId: null,
      };
      throttledEmitters.set(key, emitter);
    }

    emitter.pendingData = data;

    if (now - emitter.lastEmitTime >= throttleMs) {
      this._doEmit(key, event);
    } else if (!emitter.timeoutId) {
      const delay = throttleMs - (now - emitter.lastEmitTime);
      emitter.timeoutId = setTimeout(() => {
        this._doEmit(key, event);
      }, delay);
    }
  },

  _doEmit(key: string, event: string): void {
    const emitter = throttledEmitters.get(key);
    if (!emitter || !emitter.pendingData) return;

    socket?.emit(event, emitter.pendingData);
    emitter.lastEmitTime = Date.now();
    emitter.pendingData = null;
    if (emitter.timeoutId) {
      clearTimeout(emitter.timeoutId);
      emitter.timeoutId = null;
    }
  },

  applyIncrementalUpdate(
    existingData: Record<string, unknown>[],
    update: DashboardUpdateData,
    idKey: string = 'id',
  ): Record<string, unknown>[] {
    if (update.type === 'full') {
      return update.data as Record<string, unknown>[];
    }

    const newData = [...existingData];
    const updateData = update.data as Record<string, unknown>;
    const updateArray = Array.isArray(updateData) ? updateData : [updateData];

    updateArray.forEach((item) => {
      const id = item[idKey];
      const index = newData.findIndex((d) => d[idKey] === id);
      if (index >= 0) {
        newData[index] = { ...newData[index], ...item };
      } else {
        newData.push(item);
      }
    });

    return newData;
  },

  getSocket(): Socket | null {
    return socket;
  },

  getSubscribedDashboards(): string[] {
    return Array.from(subscribedDashboards);
  },

  getSubscribedMetrics(): string[] {
    return Array.from(subscribedMetrics);
  },
};
