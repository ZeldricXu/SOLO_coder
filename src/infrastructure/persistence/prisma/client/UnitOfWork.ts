import { IUnitOfWorkPort, IUnitOfWorkFactory, ITransaction } from '../../../../application/shared/ports/IUnitOfWorkPort';
import { getPrismaClient } from './PrismaClient';

class PrismaTransaction implements ITransaction {
  constructor(private readonly tx: any) {}

  async commit(): Promise<void> {
    // Prisma transactions auto-commit when the callback completes
  }

  async rollback(): Promise<void> {
    // Prisma transactions auto-rollback on error
  }

  getClient(): any {
    return this.tx;
  }
}

class PrismaUnitOfWork implements IUnitOfWorkPort {
  private transaction: PrismaTransaction | null = null;
  private readonly prisma: any;

  constructor() {
    this.prisma = getPrismaClient();
  }

  async start(): Promise<void> {
    // Transaction starts in execute method
  }

  async commit(): Promise<void> {
    if (this.transaction) {
      await this.transaction.commit();
      this.transaction = null;
    }
  }

  async rollback(): Promise<void> {
    if (this.transaction) {
      await this.transaction.rollback();
      this.transaction = null;
    }
  }

  async execute<T>(fn: () => Promise<T>): Promise<T> {
    let result: T | null = null;

    await this.prisma.$transaction(async (tx: any) => {
      this.transaction = new PrismaTransaction(tx);
      try {
        result = await fn();
      } catch (error) {
        this.transaction = null;
        throw error;
      }
    });

    this.transaction = null;
    return result as T;
  }

  getTransaction(): ITransaction | null {
    return this.transaction;
  }

  hasActiveTransaction(): boolean {
    return this.transaction !== null;
  }
}

export class PrismaUnitOfWorkFactory implements IUnitOfWorkFactory {
  create(): IUnitOfWorkPort {
    return new PrismaUnitOfWork();
  }
}
