"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const testing_1 = require("@nestjs/testing");
const common_1 = require("@nestjs/common");
const data_source_service_1 = require("./data-source.service");
const prisma_service_1 = require("../prisma/prisma.service");
const sql_validator_1 = require("../common/utils/sql-validator");
jest.mock('../common/utils/sql-validator');
const MockedSqlValidator = sql_validator_1.SqlValidator;
jest.mock('./connectors/connector-factory', () => ({
    ConnectorFactory: {
        create: jest.fn(),
    },
}));
const connector_factory_1 = require("./connectors/connector-factory");
describe('DataSourceService', () => {
    let service;
    let prisma;
    let mockConnector;
    const mockDataSource = {
        id: 'ds-1',
        name: 'Test DB',
        type: 'MYSQL',
        config: { host: 'localhost', port: 3306, database: 'test' },
        poolSize: 10,
        queryTimeout: 30000,
        businessLineId: 'bl-1',
        createdAt: new Date(),
        updatedAt: new Date(),
    };
    beforeEach(async () => {
        jest.clearAllMocks();
        mockConnector = {
            connect: jest.fn().mockResolvedValue(undefined),
            query: jest.fn().mockResolvedValue({
                rows: [{ id: 1, name: 'test' }],
                fields: [{ name: 'id', type: 'int' }],
                rowCount: 1,
            }),
            testConnection: jest.fn().mockResolvedValue(true),
            inferSchema: jest.fn().mockResolvedValue([]),
            disconnect: jest.fn().mockResolvedValue(undefined),
        };
        connector_factory_1.ConnectorFactory.create.mockReturnValue(mockConnector);
        prisma = {
            dataSource: {
                create: jest.fn(),
                findUnique: jest.fn(),
                findMany: jest.fn(),
                update: jest.fn(),
                delete: jest.fn(),
            },
        };
        MockedSqlValidator.validate.mockReturnValue({ safe: true });
        const module = await testing_1.Test.createTestingModule({
            providers: [
                data_source_service_1.DataSourceService,
                { provide: prisma_service_1.PrismaService, useValue: prisma },
            ],
        }).compile();
        service = module.get(data_source_service_1.DataSourceService);
    });
    describe('executeQuery - normal flow', () => {
        it('should execute a valid query and record success', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            const result = await service.executeQuery('ds-1', {
                sql: 'SELECT * FROM users',
            });
            expect(result.rows).toEqual([{ id: 1, name: 'test' }]);
            expect(mockConnector.query).toHaveBeenCalledWith('SELECT * FROM users', undefined);
            const breakers = service.circuitBreakers;
            expect(breakers.has('ds-1')).toBe(false);
        });
        it('should execute query with params', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            await service.executeQuery('ds-1', {
                sql: 'SELECT * FROM users WHERE id = ?',
                params: [1],
            });
            expect(mockConnector.query).toHaveBeenCalledWith('SELECT * FROM users WHERE id = ?', [1]);
        });
    });
    describe('executeQuery - query failure', () => {
        it('should record failure when query throws', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            mockConnector.query.mockRejectedValue(new Error('Connection lost'));
            await expect(service.executeQuery('ds-1', { sql: 'SELECT 1' })).rejects.toThrow('Connection lost');
            const breakers = service.circuitBreakers;
            const breaker = breakers.get('ds-1');
            expect(breaker.failures).toBe(1);
        });
    });
    describe('circuit breaker - trigger', () => {
        it('should open circuit breaker after 5 consecutive failures', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            mockConnector.query.mockRejectedValue(new Error('fail'));
            for (let i = 0; i < 5; i++) {
                await expect(service.executeQuery('ds-1', { sql: 'SELECT 1' })).rejects.toThrow('fail');
            }
            const breakers = service.circuitBreakers;
            const breaker = breakers.get('ds-1');
            expect(breaker.isOpen).toBe(true);
            expect(breaker.failures).toBe(5);
        });
    });
    describe('circuit breaker - open state', () => {
        it('should throw ServiceUnavailableException when circuit breaker is open', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            const breakers = service.circuitBreakers;
            breakers.set('ds-1', {
                failures: 5,
                lastFailureTime: Date.now(),
                isOpen: true,
            });
            await expect(service.executeQuery('ds-1', { sql: 'SELECT 1' })).rejects.toThrow(common_1.ServiceUnavailableException);
        });
        it('should include retry time in the error message', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            const breakers = service.circuitBreakers;
            breakers.set('ds-1', {
                failures: 5,
                lastFailureTime: Date.now(),
                isOpen: true,
            });
            try {
                await service.executeQuery('ds-1', { sql: 'SELECT 1' });
            }
            catch (error) {
                expect(error.message).toContain('Circuit breaker is open for data source ds-1');
                expect(error.message).toContain('Retry after');
            }
        });
    });
    describe('circuit breaker - recovery', () => {
        it('should reset circuit breaker after CIRCUIT_BREAKER_RESET_MS (30s)', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            const breakers = service.circuitBreakers;
            breakers.set('ds-1', {
                failures: 5,
                lastFailureTime: Date.now() - 31_000,
                isOpen: true,
            });
            mockConnector.query.mockResolvedValue({
                rows: [],
                rowCount: 0,
            });
            const result = await service.executeQuery('ds-1', {
                sql: 'SELECT 1',
            });
            expect(result).toBeDefined();
            const breaker = breakers.get('ds-1');
            expect(breaker.failures).toBe(0);
            expect(breaker.isOpen).toBe(false);
        });
    });
    describe('circuit breaker - success reset', () => {
        it('should reset failures and isOpen on recordSuccess', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            const breakers = service.circuitBreakers;
            breakers.set('ds-1', {
                failures: 3,
                lastFailureTime: Date.now(),
                isOpen: false,
            });
            mockConnector.query.mockResolvedValue({
                rows: [],
                rowCount: 0,
            });
            await service.executeQuery('ds-1', { sql: 'SELECT 1' });
            const breaker = breakers.get('ds-1');
            expect(breaker.failures).toBe(0);
            expect(breaker.isOpen).toBe(false);
        });
    });
    describe('SQL validation', () => {
        it('should throw BadRequestException for unsafe SQL', async () => {
            MockedSqlValidator.validate.mockReturnValue({
                safe: false,
                reason: 'Forbidden SQL operation detected: DROP TABLE',
            });
            await expect(service.executeQuery('ds-1', { sql: 'DROP TABLE users' })).rejects.toThrow(common_1.BadRequestException);
            await expect(service.executeQuery('ds-1', { sql: 'DROP TABLE users' })).rejects.toThrow('Forbidden SQL operation detected: DROP TABLE');
        });
        it('should validate SQL before checking circuit breaker', async () => {
            MockedSqlValidator.validate.mockReturnValue({
                safe: false,
                reason: 'Forbidden SQL operation detected: DELETE FROM',
            });
            const breakers = service.circuitBreakers;
            breakers.set('ds-1', {
                failures: 5,
                lastFailureTime: Date.now(),
                isOpen: true,
            });
            await expect(service.executeQuery('ds-1', { sql: 'DELETE FROM users' })).rejects.toThrow(common_1.BadRequestException);
        });
    });
    describe('connection pool management', () => {
        it('should cache connector in pool on first access', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            await service.executeQuery('ds-1', { sql: 'SELECT 1' });
            await service.executeQuery('ds-1', { sql: 'SELECT 2' });
            expect(connector_factory_1.ConnectorFactory.create).toHaveBeenCalledTimes(1);
            expect(mockConnector.connect).toHaveBeenCalledTimes(1);
        });
        it('should disconnect and remove connector from pool on closeConnector', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            await service.executeQuery('ds-1', { sql: 'SELECT 1' });
            const pool = service.connectionPool;
            expect(pool.has('ds-1')).toBe(true);
            await service.closeConnector('ds-1');
            expect(mockConnector.disconnect).toHaveBeenCalled();
            expect(pool.has('ds-1')).toBe(false);
        });
        it('should close connector and clear circuit breaker on update with config changes', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            prisma.dataSource.update.mockResolvedValue({
                ...mockDataSource,
                poolSize: 20,
            });
            await service.executeQuery('ds-1', { sql: 'SELECT 1' });
            const breakers = service.circuitBreakers;
            breakers.set('ds-1', {
                failures: 2,
                lastFailureTime: Date.now(),
                isOpen: false,
            });
            await service.update('ds-1', { poolSize: 20 });
            expect(mockConnector.disconnect).toHaveBeenCalled();
            expect(breakers.has('ds-1')).toBe(false);
        });
    });
    describe('onModuleDestroy', () => {
        it('should disconnect all connectors and clear circuit breakers', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            await service.executeQuery('ds-1', { sql: 'SELECT 1' });
            const secondMockConnector = {
                ...mockConnector,
                disconnect: jest.fn().mockResolvedValue(undefined),
            };
            connector_factory_1.ConnectorFactory.create.mockReturnValue(secondMockConnector);
            prisma.dataSource.findUnique.mockResolvedValue({
                ...mockDataSource,
                id: 'ds-2',
            });
            await service.executeQuery('ds-2', { sql: 'SELECT 1' });
            await service.onModuleDestroy();
            expect(mockConnector.disconnect).toHaveBeenCalled();
            expect(secondMockConnector.disconnect).toHaveBeenCalled();
            const breakers = service.circuitBreakers;
            expect(breakers.size).toBe(0);
        });
    });
    describe('findOne', () => {
        it('should throw NotFoundException if data source not found', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(null);
            await expect(service.findOne('non-existent')).rejects.toThrow(common_1.NotFoundException);
        });
    });
    describe('testConnection', () => {
        it('should test connection and update lastConnectionTest', async () => {
            prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
            prisma.dataSource.update.mockResolvedValue(mockDataSource);
            const result = await service.testConnection('ds-1');
            expect(result).toBe(true);
            expect(mockConnector.testConnection).toHaveBeenCalled();
            expect(prisma.dataSource.update).toHaveBeenCalledWith({
                where: { id: 'ds-1' },
                data: { lastConnectionTest: expect.any(Date) },
            });
        });
    });
    describe('withTimeout', () => {
        it('should resolve within timeout', async () => {
            const promise = Promise.resolve('done');
            const result = await service.withTimeout(promise, 5000);
            expect(result).toBe('done');
        });
        it('should reject with timeout error when promise takes too long', async () => {
            jest.useFakeTimers();
            const slowPromise = new Promise((resolve) => {
                setTimeout(resolve, 10000);
            });
            const resultPromise = service.withTimeout(slowPromise, 100);
            jest.advanceTimersByTime(150);
            await expect(resultPromise).rejects.toThrow('Query timed out after 100ms');
            jest.useRealTimers();
        });
    });
});
//# sourceMappingURL=data-source.service.spec.js.map