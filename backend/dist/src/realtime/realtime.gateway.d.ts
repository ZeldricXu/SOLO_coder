import { OnGatewayConnection, OnGatewayDisconnect } from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { JwtService } from '@nestjs/jwt';
import { RealtimeService } from './realtime.service';
export declare class RealtimeGateway implements OnGatewayConnection, OnGatewayDisconnect {
    private readonly jwtService;
    private readonly realtimeService;
    server: Server;
    private readonly logger;
    constructor(jwtService: JwtService, realtimeService: RealtimeService);
    handleConnection(client: Socket): Promise<void>;
    handleDisconnect(client: Socket): void;
    handleSubscribeDashboard(client: Socket, payload: {
        dashboardId: string;
    }): {
        event: string;
        data: {
            joined: string;
        };
    };
    handleUnsubscribeDashboard(client: Socket, payload: {
        dashboardId: string;
    }): {
        event: string;
        data: {
            left: string;
        };
    };
    handleSubscribeMetric(client: Socket, payload: {
        metricId: string;
    }): {
        event: string;
        data: {
            joined: string;
        };
    };
    handleUnsubscribeMetric(client: Socket, payload: {
        metricId: string;
    }): {
        event: string;
        data: {
            left: string;
        };
    };
    handleFilterUpdate(client: Socket, payload: {
        dashboardId: string;
        widgetId: string;
        filters: unknown;
        linkedWidgetIds?: string[];
    }): void;
}
