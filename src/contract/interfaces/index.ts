import { ValidationResult, MockServerConfig, ContractConfig } from '../types';

export interface ISchemaValidator<T = unknown> {
  validateSchema(schema: T): ValidationResult;
  validateRequest?(
    schema: T,
    path: string,
    method: string,
    request: { body?: unknown; params?: Record<string, string>; query?: Record<string, string> }
  ): ValidationResult;
  validateResponse?(
    schema: T,
    path: string,
    method: string,
    statusCode: number,
    response: unknown
  ): ValidationResult;
}

export interface IMockGenerator<T = unknown> {
  generate(schema: T, config?: Partial<MockServerConfig>): MockServerConfig;
}

export interface IContractProvider {
  type: 'openapi' | 'graphql';
  validator: ISchemaValidator;
  mockGenerator: IMockGenerator;
}

export interface IContractManager {
  registerContract(config: Omit<ContractConfig, 'contractId'>): ContractConfig;
  getContract(contractId: string): ContractConfig | undefined;
  listContracts(): ContractConfig[];
  validateContract(contractId: string): ValidationResult;
  validateRequest(
    contractId: string,
    path: string,
    method: string,
    request: { body?: unknown; params?: Record<string, string>; query?: Record<string, string> }
  ): ValidationResult;
  validateResponse(
    contractId: string,
    path: string,
    method: string,
    statusCode: number,
    response: unknown
  ): ValidationResult;
  startMockServer(contractId: string, port?: number): Promise<any>;
  stopMockServer(contractId: string): Promise<void>;
}

export interface ValidatorFactory {
  getValidator(type: 'openapi' | 'graphql'): ISchemaValidator;
}

export interface MockGeneratorFactory {
  getGenerator(type: 'openapi' | 'graphql'): IMockGenerator;
}
