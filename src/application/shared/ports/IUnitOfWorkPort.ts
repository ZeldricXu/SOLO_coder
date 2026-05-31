import { IUnitOfWork, ITransaction } from '../../../domain/shared/repositories/IRepository';

export { ITransaction };

export interface IUnitOfWorkPort extends IUnitOfWork {
  getTransaction(): ITransaction | null;
  hasActiveTransaction(): boolean;
}

export interface IUnitOfWorkFactory {
  create(): IUnitOfWorkPort;
}

export const UNIT_OF_WORK_FACTORY = Symbol('IUnitOfWorkFactory');
export const UNIT_OF_WORK = Symbol('IUnitOfWorkPort');
