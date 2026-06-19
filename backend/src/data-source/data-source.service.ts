import {
  Injectable,
  NotFoundException,
  ServiceUnavailableException,
  BadRequestException,
  OnModuleDestroy,
} from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { ConnectorFactory } from './connectors/connector-factory';
import { BaseConnector, QueryResult, SchemaTable } from './connectors/base.connector';
import { CreateDataSourceDto } from './dto/create-data-source.dto';
import { UpdateDataSourceDto } from './dto/update-data-source.dto';
import { QueryDto } from './dto/query.dto';
import { SqlValidator } from '../common/utils/sql-validator';

interface CircuitBreakerState {
  failures: number;
  lastFailureTime: number | null;
  isOpen: boolean;
}

@Injectable()
export class DataSourceService implements OnModuleDestroy {
  private readonly connectionPool = new Map<string, BaseConnector>();
  private readonly circuitBreakers = new Map<string, CircuitBreakerState>();
  private static readonly CIRCUIT_BREAKER_THRESHOLD = 5;
  private static readonly CIRCUIT_BREAKER_RESET_MS = 30_000;

  constructor(private readonly prisma: PrismaService) {}

  async create(dto: CreateDataSourceDto) {
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

  async findAll(businessLineId?: string) {
    const where = businessLineId ? { businessLineId } : {};
    return this.prisma.dataSource.findMany({
      where,
      orderBy: { createdAt: 'desc' },
    });
  }

  async findOne(id: string) {
    const ds = await this.prisma.dataSource.findUnique({ where: { id } });
    if (!ds) {
      throw new NotFoundException(`Data source ${id} not found`);
    }
    return ds;
  }

  async update(id: string, dto: UpdateDataSourceDto) {
    await this.findOne(id);
    const dtoAny = dto as Record<string, any>;
    if (dtoAny.config || dtoAny.poolSize || dtoAny.queryTimeout || dtoAny.type) {
      await this.closeConnector(id);
    }
    return this.prisma.dataSource.update({
      where: { id },
      data: dto as any,
    });
  }

  async remove(id: string) {
    await this.findOne(id);
    await this.closeConnector(id);
    return this.prisma.dataSource.delete({ where: { id } });
  }

  async testConnection(id: string): Promise<boolean> {
    const ds = await this.findOne(id);
    const connector = await this.getConnector(ds.id, ds.type, ds.config as Record<string, any>, ds.poolSize, ds.queryTimeout);
    const success = await connector.testConnection();
    await this.prisma.dataSource.update({
      where: { id },
      data: { lastConnectionTest: new Date() },
    });
    return success;
  }

  async executeQuery(id: string, queryDto: QueryDto): Promise<QueryResult> {
    const validation = SqlValidator.validate(queryDto.sql);
    if (!validation.safe) {
      throw new BadRequestException(validation.reason);
    }

    const ds = await this.findOne(id);
    this.checkCircuitBreaker(id);
    const connector = await this.getConnector(ds.id, ds.type, ds.config as Record<string, any>, ds.poolSize, ds.queryTimeout);
    const timeout = queryDto.timeout ?? ds.queryTimeout;
    try {
      const result = await this.withTimeout(
        connector.query(queryDto.sql, queryDto.params),
        timeout,
      );
      this.recordSuccess(id);
      return result;
    } catch (error) {
      this.recordFailure(id);
      throw error;
    }
  }

  async inferSchema(id: string): Promise<SchemaTable[]> {
    const ds = await this.findOne(id);
    const connector = await this.getConnector(ds.id, ds.type, ds.config as Record<string, any>, ds.poolSize, ds.queryTimeout);
    return connector.inferSchema();
  }

  private async getConnector(
    id: string,
    type: any,
    config: Record<string, any>,
    poolSize: number,
    queryTimeout: number,
  ): Promise<BaseConnector> {
    let connector = this.connectionPool.get(id);
    if (!connector) {
      connector = ConnectorFactory.create(type, config, poolSize, queryTimeout);
      await connector.connect();
      this.connectionPool.set(id, connector);
    }
    return connector;
  }

  private async closeConnector(id: string): Promise<void> {
    const connector = this.connectionPool.get(id);
    if (connector) {
      await connector.disconnect();
      this.connectionPool.delete(id);
      this.circuitBreakers.delete(id);
    }
  }

  private checkCircuitBreaker(id: string): void {
    const breaker = this.circuitBreakers.get(id);
    if (!breaker || !breaker.isOpen) {
      return;
    }
    const elapsed = Date.now() - (breaker.lastFailureTime ?? 0);
    if (elapsed >= DataSourceService.CIRCUIT_BREAKER_RESET_MS) {
      breaker.failures = 0;
      breaker.isOpen = false;
      return;
    }
    throw new ServiceUnavailableException(
      `Circuit breaker is open for data source ${id}. Retry after ${Math.ceil((DataSourceService.CIRCUIT_BREAKER_RESET_MS - elapsed) / 1000)}s`,
    );
  }

  private recordFailure(id: string): void {
    let breaker = this.circuitBreakers.get(id);
    if (!breaker) {
      breaker = { failures: 0, lastFailureTime: null, isOpen: false };
      this.circuitBreakers.set(id, breaker);
    }
    breaker.failures += 1;
    breaker.lastFailureTime = Date.now();
    if (breaker.failures >= DataSourceService.CIRCUIT_BREAKER_THRESHOLD) {
      breaker.isOpen = true;
    }
  }

  private recordSuccess(id: string): void {
    const breaker = this.circuitBreakers.get(id);
    if (breaker) {
      breaker.failures = 0;
      breaker.isOpen = false;
    }
  }

  private withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
    return new Promise<T>((resolve, reject) => {
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
    const closePromises: Promise<void>[] = [];
    for (const [id, connector] of this.connectionPool.entries()) {
      closePromises.push(connector.disconnect());
      this.connectionPool.delete(id);
    }
    await Promise.all(closePromises);
    this.circuitBreakers.clear();
  }
}
