import { OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { RealtimeGateway } from './realtime.gateway';
export declare class RealtimeService implements OnModuleInit, OnModuleDestroy {
    private readonly configService;
    private readonly gateway;
    private readonly logger;
    private publisher;
    private subscriber;
    private throttleMs;
    private throttleMap;
    constructor(configService: ConfigService, gateway: RealtimeGateway);
    onModuleInit(): Promise<void>;
    onModuleDestroy(): Promise<void>;
    private getServer;
    private pushToRoom;
    private mergeUpdates;
    onMetricUpdate(metricId: string, data: Record<string, unknown>): Promise<void>;
    onAlertTrigger(alertData: Record<string, unknown> & {
        businessLineId: string;
    }): Promise<void>;
    onDataChange(businessLineId: string, data: Record<string, unknown>): Promise<void>;
    broadcastToDashboard(dashboardId: string, event: string, data: unknown): void;
    broadcastToBusinessLine(businessLineId: string, event: string, data: unknown): void;
}
