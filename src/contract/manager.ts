import { ContractConfig, ValidationResult } from './types';
import { IContractManager } from './interfaces';
import { contractProviderFactory } from './providers/ContractProviderFactory';
import { MockServer } from './mockServer';
import { generateId, logger } from '../utils/common';

export class ContractManager implements IContractManager {
  private contracts: Map<string, ContractConfig> = new Map();
  private mockServers: Map<string, MockServer> = new Map();

  registerContract(config: Omit<ContractConfig, 'contractId'>): ContractConfig {
    const contractId = generateId('contract_');
    const contract: ContractConfig = { ...config, contractId } as ContractConfig;

    this.contracts.set(contractId, contract);
    logger.info(`Contract registered`, { contractId, name: contract.name, type: contract.type });

    return contract;
  }

  getContract(contractId: string): ContractConfig | undefined {
    return this.contracts.get(contractId);
  }

  listContracts(): ContractConfig[] {
    return Array.from(this.contracts.values());
  }

  validateContract(contractId: string): ValidationResult {
    const contract = this.contracts.get(contractId);
    if (!contract) {
      return {
        valid: false,
        errors: [{ path: '', message: 'Contract not found' }],
        warnings: [],
      };
    }

    const validator = contractProviderFactory.getValidator(contract.type);
    if (!validator) {
      return {
        valid: false,
        errors: [{ path: '', message: `No validator found for type ${contract.type}` }],
        warnings: [],
      };
    }

    return validator.validateSchema(contract.schema as any);
  }

  validateRequest(
    contractId: string,
    path: string,
    method: string,
    request: { body?: unknown; params?: Record<string, string>; query?: Record<string, string> }
  ): ValidationResult {
    const contract = this.contracts.get(contractId);
    if (!contract) {
      return {
        valid: false,
        errors: [{ path: '', message: 'Contract not found' }],
        warnings: [],
      };
    }

    if (!contract.validation?.enabled) {
      return { valid: true, errors: [], warnings: ['Validation disabled'] };
    }

    const validator = contractProviderFactory.getValidator(contract.type);
    if (!validator || !validator.validateRequest) {
      return {
        valid: true,
        errors: [],
        warnings: [`Request validation not implemented for ${contract.type}`],
      };
    }

    return validator.validateRequest(contract.schema as any, path, method, request);
  }

  validateResponse(
    contractId: string,
    path: string,
    method: string,
    statusCode: number,
    response: unknown
  ): ValidationResult {
    const contract = this.contracts.get(contractId);
    if (!contract) {
      return {
        valid: false,
        errors: [{ path: '', message: 'Contract not found' }],
        warnings: [],
      };
    }

    if (!contract.validation?.enabled) {
      return { valid: true, errors: [], warnings: ['Validation disabled'] };
    }

    const validator = contractProviderFactory.getValidator(contract.type);
    if (!validator || !validator.validateResponse) {
      return {
        valid: true,
        errors: [],
        warnings: [`Response validation not implemented for ${contract.type}`],
      };
    }

    return validator.validateResponse(contract.schema as any, path, method, statusCode, response);
  }

  async startMockServer(contractId: string, port?: number): Promise<MockServer> {
    const contract = this.contracts.get(contractId);
    if (!contract) {
      throw new Error('Contract not found');
    }

    if (this.mockServers.has(contractId)) {
      return this.mockServers.get(contractId)!;
    }

    const mockGenerator = contractProviderFactory.getMockGenerator(contract.type);
    if (!mockGenerator) {
      throw new Error(`No mock generator found for type ${contract.type}`);
    }

    const mockConfig = mockGenerator.generate(contract.schema as any, {
      port: port || contract.mockConfig?.port,
    });

    const server = new MockServer(mockConfig);
    server.setDelay(contract.mockConfig?.delayMs || 0);
    server.setErrorRate(contract.mockConfig?.errorRate || 0);

    await server.start();
    this.mockServers.set(contractId, server);

    return server;
  }

  async stopMockServer(contractId: string): Promise<void> {
    const server = this.mockServers.get(contractId);
    if (server) {
      await server.stop();
      this.mockServers.delete(contractId);
    }
  }

  updateContract(contractId: string, updates: Partial<ContractConfig>): ContractConfig | undefined {
    const contract = this.contracts.get(contractId);
    if (!contract) return undefined;

    const updated: ContractConfig = { ...contract, ...updates };
    this.contracts.set(contractId, updated);
    logger.info(`Contract updated`, { contractId });

    return updated;
  }

  deleteContract(contractId: string): boolean {
    this.stopMockServer(contractId).catch(() => {});
    return this.contracts.delete(contractId);
  }

  getProviderFactory() {
    return contractProviderFactory;
  }
}

export const contractManager = new ContractManager();
