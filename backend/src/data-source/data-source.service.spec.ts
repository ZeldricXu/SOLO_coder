import { Test, TestingModule } from '@nestjs/testing';
import {
  NotFoundException,
  ServiceUnavailableException,
  BadRequestException,
} from '@nestjs/common';
import { DataSourceService } from './data-source.service';
import { PrismaService } from '../prisma/prisma.service';
import { SqlValidator } from '../common/utils/sql-validator';

jest.mock('../common/utils/sql-validator');

const MockedSqlValidator = SqlValidator as jest.Mocked<typeof SqlValidator>;

jest.mock('./connectors/connector-factory', () => ({
  ConnectorFactory: {
    create: jest.fn(),
  },
}));

import { ConnectorFactory } from './connectors/connector-factory';

describe('DataSourceService', () => {
  let service: DataSourceService;
  let prisma: {
    dataSource: {
      create: jest.Mock;
      findUnique: jest.Mock;
      findMany: jest.Mock;
      update: jest.Mock;
      delete: jest.Mock;
    };
  };
  let mockConnector: {
    connect: jest.Mock;
    query: jest.Mock;
    testConnection: jest.Mock;
    inferSchema: jest.Mock;
    disconnect: jest.Mock;
  };

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

    (ConnectorFactory.create as jest.Mock).mockReturnValue(mockConnector);

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

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        DataSourceService,
        { provide: PrismaService, useValue: prisma },
      ],
    }).compile();

    service = module.get<DataSourceService>(DataSourceService);
  });

  describe('executeQuery - normal flow', () => {
    it('should execute a valid query and record success', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);

      const result = await service.executeQuery('ds-1', {
        sql: 'SELECT * FROM users',
      } as any);

      expect(result.rows).toEqual([{ id: 1, name: 'test' }]);
      expect(mockConnector.query).toHaveBeenCalledWith('SELECT * FROM users', undefined);

      const breakers = (service as any).circuitBreakers as Map<string, any>;
      expect(breakers.has('ds-1')).toBe(false);
    });

    it('should execute query with params', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);

      await service.executeQuery('ds-1', {
        sql: 'SELECT * FROM users WHERE id = ?',
        params: [1],
      } as any);

      expect(mockConnector.query).toHaveBeenCalledWith(
        'SELECT * FROM users WHERE id = ?',
        [1],
      );
    });
  });

  describe('executeQuery - query failure', () => {
    it('should record failure when query throws', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
      mockConnector.query.mockRejectedValue(new Error('Connection lost'));

      await expect(
        service.executeQuery('ds-1', { sql: 'SELECT 1' } as any),
      ).rejects.toThrow('Connection lost');

      const breakers = (service as any).circuitBreakers as Map<string, any>;
      const breaker = breakers.get('ds-1');
      expect(breaker.failures).toBe(1);
    });
  });

  describe('circuit breaker - trigger', () => {
    it('should open circuit breaker after 5 consecutive failures', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
      mockConnector.query.mockRejectedValue(new Error('fail'));

      for (let i = 0; i < 5; i++) {
        await expect(
          service.executeQuery('ds-1', { sql: 'SELECT 1' } as any),
        ).rejects.toThrow('fail');
      }

      const breakers = (service as any).circuitBreakers as Map<string, any>;
      const breaker = breakers.get('ds-1');
      expect(breaker.isOpen).toBe(true);
      expect(breaker.failures).toBe(5);
    });
  });

  describe('circuit breaker - open state', () => {
    it('should throw ServiceUnavailableException when circuit breaker is open', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);

      const breakers = (service as any).circuitBreakers as Map<string, any>;
      breakers.set('ds-1', {
        failures: 5,
        lastFailureTime: Date.now(),
        isOpen: true,
      });

      await expect(
        service.executeQuery('ds-1', { sql: 'SELECT 1' } as any),
      ).rejects.toThrow(ServiceUnavailableException);
    });

    it('should include retry time in the error message', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);

      const breakers = (service as any).circuitBreakers as Map<string, any>;
      breakers.set('ds-1', {
        failures: 5,
        lastFailureTime: Date.now(),
        isOpen: true,
      });

      try {
        await service.executeQuery('ds-1', { sql: 'SELECT 1' } as any);
      } catch (error) {
        expect(error.message).toContain('Circuit breaker is open for data source ds-1');
        expect(error.message).toContain('Retry after');
      }
    });
  });

  describe('circuit breaker - recovery', () => {
    it('should reset circuit breaker after CIRCUIT_BREAKER_RESET_MS (30s)', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);

      const breakers = (service as any).circuitBreakers as Map<string, any>;
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
      } as any);

      expect(result).toBeDefined();
      const breaker = breakers.get('ds-1');
      expect(breaker.failures).toBe(0);
      expect(breaker.isOpen).toBe(false);
    });
  });

  describe('circuit breaker - success reset', () => {
    it('should reset failures and isOpen on recordSuccess', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);

      const breakers = (service as any).circuitBreakers as Map<string, any>;
      breakers.set('ds-1', {
        failures: 3,
        lastFailureTime: Date.now(),
        isOpen: false,
      });

      mockConnector.query.mockResolvedValue({
        rows: [],
        rowCount: 0,
      });

      await service.executeQuery('ds-1', { sql: 'SELECT 1' } as any);

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

      await expect(
        service.executeQuery('ds-1', { sql: 'DROP TABLE users' } as any),
      ).rejects.toThrow(BadRequestException);

      await expect(
        service.executeQuery('ds-1', { sql: 'DROP TABLE users' } as any),
      ).rejects.toThrow('Forbidden SQL operation detected: DROP TABLE');
    });

    it('should validate SQL before checking circuit breaker', async () => {
      MockedSqlValidator.validate.mockReturnValue({
        safe: false,
        reason: 'Forbidden SQL operation detected: DELETE FROM',
      });

      const breakers = (service as any).circuitBreakers as Map<string, any>;
      breakers.set('ds-1', {
        failures: 5,
        lastFailureTime: Date.now(),
        isOpen: true,
      });

      await expect(
        service.executeQuery('ds-1', { sql: 'DELETE FROM users' } as any),
      ).rejects.toThrow(BadRequestException);
    });
  });

  describe('connection pool management', () => {
    it('should cache connector in pool on first access', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);

      await service.executeQuery('ds-1', { sql: 'SELECT 1' } as any);
      await service.executeQuery('ds-1', { sql: 'SELECT 2' } as any);

      expect(ConnectorFactory.create).toHaveBeenCalledTimes(1);
      expect(mockConnector.connect).toHaveBeenCalledTimes(1);
    });

    it('should disconnect and remove connector from pool on closeConnector', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);

      await service.executeQuery('ds-1', { sql: 'SELECT 1' } as any);

      const pool = (service as any).connectionPool as Map<string, any>;
      expect(pool.has('ds-1')).toBe(true);

      await (service as any).closeConnector('ds-1');

      expect(mockConnector.disconnect).toHaveBeenCalled();
      expect(pool.has('ds-1')).toBe(false);
    });

    it('should close connector and clear circuit breaker on update with config changes', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);
      prisma.dataSource.update.mockResolvedValue({
        ...mockDataSource,
        poolSize: 20,
      });

      await service.executeQuery('ds-1', { sql: 'SELECT 1' } as any);

      const breakers = (service as any).circuitBreakers as Map<string, any>;
      breakers.set('ds-1', {
        failures: 2,
        lastFailureTime: Date.now(),
        isOpen: false,
      });

      await service.update('ds-1', { poolSize: 20 } as any);

      expect(mockConnector.disconnect).toHaveBeenCalled();
      expect(breakers.has('ds-1')).toBe(false);
    });
  });

  describe('onModuleDestroy', () => {
    it('should disconnect all connectors and clear circuit breakers', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(mockDataSource);

      await service.executeQuery('ds-1', { sql: 'SELECT 1' } as any);

      const secondMockConnector = {
        ...mockConnector,
        disconnect: jest.fn().mockResolvedValue(undefined),
      };
      (ConnectorFactory.create as jest.Mock).mockReturnValue(secondMockConnector);
      prisma.dataSource.findUnique.mockResolvedValue({
        ...mockDataSource,
        id: 'ds-2',
      });

      await service.executeQuery('ds-2', { sql: 'SELECT 1' } as any);

      await service.onModuleDestroy();

      expect(mockConnector.disconnect).toHaveBeenCalled();
      expect(secondMockConnector.disconnect).toHaveBeenCalled();

      const breakers = (service as any).circuitBreakers as Map<string, any>;
      expect(breakers.size).toBe(0);
    });
  });

  describe('findOne', () => {
    it('should throw NotFoundException if data source not found', async () => {
      prisma.dataSource.findUnique.mockResolvedValue(null);

      await expect(service.findOne('non-existent')).rejects.toThrow(
        NotFoundException,
      );
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
      const result = await (service as any).withTimeout(promise, 5000);
      expect(result).toBe('done');
    });

    it('should reject with timeout error when promise takes too long', async () => {
      jest.useFakeTimers();
      const slowPromise = new Promise((resolve) => {
        setTimeout(resolve, 10000);
      });

      const resultPromise = (service as any).withTimeout(slowPromise, 100);

      jest.advanceTimersByTime(150);

      await expect(resultPromise).rejects.toThrow('Query timed out after 100ms');
      jest.useRealTimers();
    });
  });
});
