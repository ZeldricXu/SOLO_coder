import { io, Socket } from 'socket.io-client';

let socket: Socket | null = null;

export const realtimeService = {
  connect(token: string) {
    if (socket?.connected) return;

    socket = io('/', {
      auth: { token },
      transports: ['websocket', 'polling'],
    });

    socket.on('connect', () => {
      console.log('[Realtime] connected');
    });

    socket.on('disconnect', (reason) => {
      console.log('[Realtime] disconnected:', reason);
    });

    socket.on('connect_error', (err) => {
      console.error('[Realtime] connection error:', err.message);
    });
  },

  disconnect() {
    if (socket) {
      socket.disconnect();
      socket = null;
    }
  },

  subscribeDashboard(dashboardId: string) {
    socket?.emit('subscribe:dashboard', { dashboardId });
  },

  unsubscribeDashboard(dashboardId: string) {
    socket?.emit('unsubscribe:dashboard', { dashboardId });
  },

  subscribeMetric(metricId: string) {
    socket?.emit('subscribe:metric', { metricId });
  },

  unsubscribeMetric(metricId: string) {
    socket?.emit('unsubscribe:metric', { metricId });
  },

  onDashboardUpdate(callback: (data: unknown) => void) {
    socket?.on('dashboard:update', callback);
    return () => {
      socket?.off('dashboard:update', callback);
    };
  },

  onMetricUpdate(callback: (data: unknown) => void) {
    socket?.on('metric:update', callback);
    return () => {
      socket?.off('metric:update', callback);
    };
  },

  onAlertTrigger(callback: (data: unknown) => void) {
    socket?.on('alert:trigger', callback);
    return () => {
      socket?.off('alert:trigger', callback);
    };
  },

  onFilterUpdate(callback: (data: unknown) => void) {
    socket?.on('filter:update', callback);
    return () => {
      socket?.off('filter:update', callback);
    };
  },

  emitFilterUpdate(dashboardId: string, widgetId: string, filters: unknown, linkedWidgetIds?: string[]) {
    socket?.emit('filter:update', {
      dashboardId,
      widgetId,
      filters,
      linkedWidgetIds,
    });
  },

  getSocket(): Socket | null {
    return socket;
  },
};
