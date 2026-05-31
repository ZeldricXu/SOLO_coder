import { AggregateRoot } from '../entities/AggregateRoot';
import { UniqueEntityID } from '../value-objects/UniqueEntityID';

export interface IRepository<TAggregate extends AggregateRoot<UniqueEntityID>, TId = UniqueEntityID> {
  findById(id: UniqueEntityID, tenantId: string): Promise<TAggregate | null>;
  findAll(tenantId: string, options?: { skip?: number; take?: number }): Promise<TAggregate[]>;
  count(tenantId: string): Promise<number>;
  save(aggregate: TAggregate, tenantId: string): Promise<TAggregate>;
  delete(id: UniqueEntityID, tenantId: string): Promise<void>;
}

export interface IPaginatedResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface IPaginationParams {
  page: number;
  pageSize: number;
}

export interface IUnitOfWork {
  start(): Promise<void>;
  commit(): Promise<void>;
  rollback(): Promise<void>;
  execute<T>(fn: () => Promise<T>): Promise<T>;
}

export interface ITransaction {
  commit(): Promise<void>;
  rollback(): Promise<void>;
}
