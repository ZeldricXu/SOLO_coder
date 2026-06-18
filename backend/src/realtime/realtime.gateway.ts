import {
  WebSocketGateway,
  WebSocketServer,
  SubscribeMessage,
  OnGatewayConnection,
  OnGatewayDisconnect,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { JwtService } from '@nestjs/jwt';
import { forwardRef, Inject, Logger } from '@nestjs/common';
import { RealtimeService } from './realtime.service';

@WebSocketGateway({
  cors: { origin: '*' },
  transports: ['websocket', 'polling'],
})
export class RealtimeGateway
  implements OnGatewayConnection, OnGatewayDisconnect
{
  @WebSocketServer()
  server: Server;

  private readonly logger = new Logger(RealtimeGateway.name);

  constructor(
    private readonly jwtService: JwtService,
    @Inject(forwardRef(() => RealtimeService))
    private readonly realtimeService: RealtimeService,
  ) {}

  async handleConnection(client: Socket) {
    const token = client.handshake.auth?.token as string;
    if (!token) {
      this.logger.warn(`Client ${client.id} disconnected: no token provided`);
      client.disconnect(true);
      return;
    }

    try {
      const payload = await this.jwtService.verifyAsync(token);
      client.data.user = payload;

      if (payload.businessLineId) {
        client.join(`businessLine:${payload.businessLineId}`);
      }
      if (payload.dashboardId) {
        client.join(`dashboard:${payload.dashboardId}`);
      }

      this.logger.log(`Client ${client.id} connected as user ${payload.sub || payload.email}`);
    } catch {
      this.logger.warn(`Client ${client.id} disconnected: invalid token`);
      client.disconnect(true);
    }
  }

  handleDisconnect(client: Socket) {
    this.logger.log(`Client ${client.id} disconnected`);
  }

  @SubscribeMessage('subscribe:dashboard')
  handleSubscribeDashboard(
    client: Socket,
    payload: { dashboardId: string },
  ) {
    client.join(`dashboard:${payload.dashboardId}`);
    return { event: 'subscribe:dashboard', data: { joined: payload.dashboardId } };
  }

  @SubscribeMessage('unsubscribe:dashboard')
  handleUnsubscribeDashboard(
    client: Socket,
    payload: { dashboardId: string },
  ) {
    client.leave(`dashboard:${payload.dashboardId}`);
    return { event: 'unsubscribe:dashboard', data: { left: payload.dashboardId } };
  }

  @SubscribeMessage('subscribe:metric')
  handleSubscribeMetric(
    client: Socket,
    payload: { metricId: string },
  ) {
    client.join(`metric:${payload.metricId}`);
    return { event: 'subscribe:metric', data: { joined: payload.metricId } };
  }

  @SubscribeMessage('unsubscribe:metric')
  handleUnsubscribeMetric(
    client: Socket,
    payload: { metricId: string },
  ) {
    client.leave(`metric:${payload.metricId}`);
    return { event: 'unsubscribe:metric', data: { left: payload.metricId } };
  }

  @SubscribeMessage('filter:update')
  handleFilterUpdate(
    client: Socket,
    payload: { dashboardId: string; widgetId: string; filters: unknown; linkedWidgetIds?: string[] },
  ) {
    this.realtimeService.broadcastToDashboard(
      payload.dashboardId,
      'filter:update',
      {
        widgetId: payload.widgetId,
        filters: payload.filters,
        linkedWidgetIds: payload.linkedWidgetIds,
        updatedBy: client.data.user?.sub || client.data.user?.email,
      },
    );
  }
}
