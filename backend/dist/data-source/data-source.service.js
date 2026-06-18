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
var DataSourceService_1;
Object.defineProperty(exports, "__esModule", { value: true });
exports.DataSourceService = void 0;
const common_1 = require("@nestjs/common");
const prisma_service_1 = require("../prisma/prisma.service");
const connector_factory_1 = require("./connectors/connector-factory");
let DataSourceService = DataSourceService_1 = class DataSourceService {
    constructor(prisma) {
        this.prisma = prisma;
        this.connectionPool = new Map();
        this.circuitBreakers = new Map();
    }
    async create(dto) {
        return this.prisma.dataSource.create({
            data: {
                name: dto.name,
                type: dto.type,
                config: dto.config,
                poolSize: dto.poolSize ?? 10,
                queryTimeout: dto.queryTimeout ?? 30_000,
                businessLineId: dto.businessLineId,
            },
        });
    }
    async findAll(businessLineId) {
        const where = businessLineId ? { businessLineId } : {};
        return this.prisma.dataSource.findMany({
            where,
            orderBy: { createdAt: 'desc' },
        });
    }
    async findOne(id) {
        const ds = await this.prisma.dataSource.findUnique({ where: { id } });
        if (!ds) {
            throw new common_1.NotFoundException(`Data source ${id} not found`);
        }
        return ds;
    }
    async update(id, dto) {
        await this.findOne(id);
        const dtoAny = dto;
        if (dtoAny.config || dtoAny.poolSize || dtoAny.queryTimeout || dtoAny.type) {
            await this.closeConnector(id);
        }
        return this.prisma.dataSource.update({
            where: { id },
            data: dto,
        });
    }
    async remove(id) {
        await this.findOne(id);
        await this.closeConnector(id);
        return this.prisma.dataSource.delete({ where: { id } });
    }
    async testConnection(id) {
        const ds = await this.findOne(id);
        const connector = await this.getConnector(ds.id, ds.type, ds.config, ds.poolSize, ds.queryTimeout);
        const success = await connector.testConnection();
        await this.prisma.dataSource.update({
            where: { id },
            data: { lastConnectionTest: new Date() },
        });
        return success;
    }
    async executeQuery(id, queryDto) {
        const ds = await this.findOne(id);
        this.checkCircuitBreaker(id);
        const connector = await this.getConnector(ds.id, ds.type, ds.config, ds.poolSize, ds.queryTimeout);
        const timeout = queryDto.timeout ?? ds.queryTimeout;
        try {
            const result = await this.withTimeout(connector.query(queryDto.sql, queryDto.params), timeout);
            this.recordSuccess(id);
            return result;
        }
        catch (error) {
            this.recordFailure(id);
            throw error;
        }
    }
    async inferSchema(id) {
        const ds = await this.findOne(id);
        const connector = await this.getConnector(ds.id, ds.type, ds.config, ds.poolSize, ds.queryTimeout);
        return connector.inferSchema();
    }
    async getConnector(id, type, config, poolSize, queryTimeout) {
        let connector = this.connectionPool.get(id);
        if (!connector) {
            connector = connector_factory_1.ConnectorFactory.create(type, config, poolSize, queryTimeout);
            await connector.connect();
            this.connectionPool.set(id, connector);
        }
        return connector;
    }
    async closeConnector(id) {
        const connector = this.connectionPool.get(id);
        if (connector) {
            await connector.disconnect();
            this.connectionPool.delete(id);
            this.circuitBreakers.delete(id);
        }
    }
    checkCircuitBreaker(id) {
        const breaker = this.circuitBreakers.get(id);
        if (!breaker || !breaker.isOpen) {
            return;
        }
        const elapsed = Date.now() - (breaker.lastFailureTime ?? 0);
        if (elapsed >= DataSourceService_1.CIRCUIT_BREAKER_RESET_MS) {
            breaker.failures = 0;
            breaker.isOpen = false;
            return;
        }
        throw new common_1.ServiceUnavailableException(`Circuit breaker is open for data source ${id}. Retry after ${Math.ceil((DataSourceService_1.CIRCUIT_BREAKER_RESET_MS - elapsed) / 1000)}s`);
    }
    recordFailure(id) {
        let breaker = this.circuitBreakers.get(id);
        if (!breaker) {
            breaker = { failures: 0, lastFailureTime: null, isOpen: false };
            this.circuitBreakers.set(id, breaker);
        }
        breaker.failures += 1;
        breaker.lastFailureTime = Date.now();
        if (breaker.failures >= DataSourceService_1.CIRCUIT_BREAKER_THRESHOLD) {
            breaker.isOpen = true;
        }
    }
    recordSuccess(id) {
        const breaker = this.circuitBreakers.get(id);
        if (breaker) {
            breaker.failures = 0;
            breaker.isOpen = false;
        }
    }
    withTimeout(promise, ms) {
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error(`Query timed out after ${ms}ms`)), ms);
            promise
                .then((value) => {
                clearTimeout(timer);
                resolve(value);
            })
                .catch((error) => {
                clearTimeout(timer);
                reject(error);
            });
        });
    }
    async onModuleDestroy() {
        const closePromises = [];
        for (const [id, connector] of this.connectionPool.entries()) {
            closePromises.push(connector.disconnect());
            this.connectionPool.delete(id);
        }
        await Promise.all(closePromises);
        this.circuitBreakers.clear();
    }
};
exports.DataSourceService = DataSourceService;
DataSourceService.CIRCUIT_BREAKER_THRESHOLD = 5;
DataSourceService.CIRCUIT_BREAKER_RESET_MS = 30_000;
exports.DataSourceService = DataSourceService = DataSourceService_1 = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [prisma_service_1.PrismaService])
], DataSourceService);
//# sourceMappingURL=data-source.service.js.map