"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var ChangeDetectorService_1;
Object.defineProperty(exports, "__esModule", { value: true });
exports.ChangeDetectorService = void 0;
const common_1 = require("@nestjs/common");
const client_1 = require("@prisma/client");
const prisma_service_1 = require("../prisma/prisma.service");
const data_source_service_1 = require("../data-source/data-source.service");
const realtime_service_1 = require("../realtime/realtime.service");
const detector_factory_1 = require("./detector-factory");
let ChangeDetectorService = ChangeDetectorService_1 = class ChangeDetectorService {
    constructor(prisma, dataSourceService, realtimeService) {
        this.prisma = prisma;
        this.dataSourceService = dataSourceService;
        this.realtimeService = realtimeService;
        this.logger = new common_1.Logger(ChangeDetectorService_1.name);
        this.detectors = new Map();
        this.dataSourceBusinessLineMap = new Map();
    }
    async onModuleInit() {
        this.logger.log('Initializing ChangeDetectorService...');
        try {
            const allDataSources = await this.dataSourceService.findAll();
            const supportedTypes = [client_1.DataSourceType.MYSQL, client_1.DataSourceType.CLICKHOUSE];
            const eligibleDataSources = allDataSources.filter((ds) => supportedTypes.includes(ds.type) && ds.isActive !== false);
            this.logger.log(`Found ${eligibleDataSources.length} eligible data sources (${supportedTypes.join('/')}) for change detection`);
            for (const ds of eligibleDataSources) {
                try {
                    await this.startDetector(ds.id);
                }
                catch (err) {
                    this.logger.error(`Failed to start detector for dataSource ${ds.id} (${ds.name}): ${err?.message ?? err}`);
                }
            }
        }
        catch (err) {
            this.logger.error(`Failed to initialize change detectors: ${err?.message ?? err}`);
        }
    }
    async onModuleDestroy() {
        this.logger.log('Shutting down all change detectors...');
        const stopPromises = [];
        for (const [dataSourceId, detector] of this.detectors.entries()) {
            this.logger.debug(`Stopping detector for dataSource ${dataSourceId}`);
            stopPromises.push(detector.stop());
            this.detectors.delete(dataSourceId);
        }
        await Promise.all(stopPromises);
        this.dataSourceBusinessLineMap.clear();
        this.logger.log('All change detectors stopped');
    }
    async startDetector(dataSourceId) {
        if (this.detectors.has(dataSourceId)) {
            this.logger.warn(`Detector already running for dataSource ${dataSourceId}`);
            return;
        }
        const ds = await this.dataSourceService.findOne(dataSourceId);
        const supportedTypes = [client_1.DataSourceType.MYSQL, client_1.DataSourceType.CLICKHOUSE];
        if (!supportedTypes.includes(ds.type)) {
            this.logger.warn(`Change detection not supported for dataSource ${dataSourceId} type ${ds.type}, skipping`);
            return;
        }
        this.dataSourceBusinessLineMap.set(dataSourceId, ds.businessLineId);
        const detector = detector_factory_1.DetectorFactory.create(ds.type, dataSourceId, ds.config);
        detector.onEvent((event) => this.handleChangeEvent(event));
        this.detectors.set(dataSourceId, detector);
        await detector.start();
        this.logger.log(`Started detector for dataSource ${dataSourceId} (${ds.name}, type=${ds.type})`);
    }
    async stopDetector(dataSourceId) {
        const detector = this.detectors.get(dataSourceId);
        if (!detector) {
            throw new common_1.NotFoundException(`No detector running for dataSource ${dataSourceId}`);
        }
        await detector.stop();
        this.detectors.delete(dataSourceId);
        this.dataSourceBusinessLineMap.delete(dataSourceId);
        this.logger.log(`Stopped detector for dataSource ${dataSourceId}`);
    }
    getDetectorStatus(dataSourceId) {
        const detector = this.detectors.get(dataSourceId);
        if (!detector) {
            return { running: false };
        }
        return { running: detector.isRunning ?? false };
    }
    getAllDetectorStatuses() {
        const result = [];
        for (const [dataSourceId, detector] of this.detectors.entries()) {
            result.push({
                dataSourceId,
                running: detector.isRunning ?? false,
            });
        }
        return result;
    }
    async handleChangeEvent(event) {
        try {
            const businessLineId = this.dataSourceBusinessLineMap.get(event.dataSourceId);
            const prismaAny = this.prisma;
            const created = await prismaAny.dataChangeEvent.create({
                data: {
                    dataSourceId: event.dataSourceId,
                    tableName: event.tableName,
                    operation: event.operation,
                    pk: event.pk ?? null,
                    beforeData: event.beforeData ?? null,
                    afterData: event.afterData ?? null,
                    timestamp: event.timestamp,
                    delivered: false,
                },
            });
            if (businessLineId) {
                await this.realtimeService.onDataChange(businessLineId, {
                    eventId: created.id,
                    dataSourceId: event.dataSourceId,
                    tableName: event.tableName,
                    operation: event.operation,
                    pk: event.pk,
                    beforeData: event.beforeData,
                    afterData: event.afterData,
                    timestamp: event.timestamp,
                });
                await prismaAny.dataChangeEvent.update({
                    where: { id: created.id },
                    data: { delivered: true },
                });
            }
            this.logger.debug(`Change event handled: [${event.operation}] ${event.dataSourceId}.${event.tableName}`);
        }
        catch (err) {
            this.logger.error(`Failed to handle change event for dataSource ${event.dataSourceId}.${event.tableName}: ${err?.message ?? err}`);
        }
    }
};
exports.ChangeDetectorService = ChangeDetectorService;
exports.ChangeDetectorService = ChangeDetectorService = ChangeDetectorService_1 = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [prisma_service_1.PrismaService,
        data_source_service_1.DataSourceService,
        realtime_service_1.RealtimeService])
], ChangeDetectorService);
//# sourceMappingURL=change-detector.service.js.map