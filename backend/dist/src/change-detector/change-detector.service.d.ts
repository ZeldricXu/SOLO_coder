import { OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { DataSourceService } from '../data-source/data-source.service';
import { RealtimeService } from '../realtime/realtime.service';
export declare class ChangeDetectorService implements OnModuleInit, OnModuleDestroy {
    private readonly prisma;
    private readonly dataSourceService;
    private readonly realtimeService;
    private readonly logger;
    private detectors;
    private dataSourceBusinessLineMap;
    constructor(prisma: PrismaService, dataSourceService: DataSourceService, realtimeService: RealtimeService);
    onModuleInit(): Promise<void>;
    onModuleDestroy(): Promise<void>;
    startDetector(dataSourceId: string): Promise<void>;
    stopDetector(dataSourceId: string): Promise<void>;
    getDetectorStatus(dataSourceId: string): {
        running: boolean;
    };
    getAllDetectorStatuses(): Array<{
        dataSourceId: string;
        running: boolean;
    }>;
    private handleChangeEvent;
}
