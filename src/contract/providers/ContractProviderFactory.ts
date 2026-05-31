import { IContractProvider, ISchemaValidator, IMockGenerator } from '../interfaces';
import { openAPIValidator } from '../validators/OpenAPIValidator';
import { graphQLValidator } from '../validators/GraphQLValidator';
import { openAPIMockGenerator } from '../generators/OpenAPIMockGenerator';
import { graphQLMockGenerator } from '../generators/GraphQLMockGenerator';

export class ContractProviderFactory {
  private providers: Map<string, IContractProvider> = new Map();

  constructor() {
    this.registerProvider({
      type: 'openapi',
      validator: openAPIValidator,
      mockGenerator: openAPIMockGenerator,
    });

    this.registerProvider({
      type: 'graphql',
      validator: graphQLValidator,
      mockGenerator: graphQLMockGenerator,
    });
  }

  registerProvider(provider: IContractProvider): void {
    this.providers.set(provider.type, provider);
  }

  getProvider(type: 'openapi' | 'graphql'): IContractProvider | undefined {
    return this.providers.get(type);
  }

  getValidator(type: 'openapi' | 'graphql'): ISchemaValidator | undefined {
    return this.providers.get(type)?.validator;
  }

  getMockGenerator(type: 'openapi' | 'graphql'): IMockGenerator | undefined {
    return this.providers.get(type)?.mockGenerator;
  }

  getSupportedTypes(): string[] {
    return Array.from(this.providers.keys());
  }
}

export const contractProviderFactory = new ContractProviderFactory();
