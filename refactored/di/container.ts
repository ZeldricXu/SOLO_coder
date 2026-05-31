import { Class, Factory, DIContainer, Provider } from './types';

export class Container implements DIContainer {
  private bindings: Map<symbol, Provider> = new Map();
  private instances: Map<symbol, unknown> = new Map();

  public bind<T>(token: symbol, implementation: Class<T>): void {
    this.bindings.set(token, {
      type: 'class',
      token,
      implementation
    });
  }

  public bindFactory<T>(token: symbol, factory: Factory<T>): void {
    this.bindings.set(token, {
      type: 'factory',
      token,
      factory
    });
  }

  public bindValue<T>(token: symbol, value: T): void {
    this.bindings.set(token, {
      type: 'value',
      token,
      value
    });
  }

  public get<T>(token: symbol): T {
    if (this.instances.has(token)) {
      return this.instances.get(token) as T;
    }

    const binding = this.bindings.get(token);
    if (!binding) {
      throw new Error(`No binding found for symbol: ${String(token)}`);
    }

    let instance: T;
    switch (binding.type) {
      case 'value':
        instance = binding.value as T;
        break;
      case 'factory':
        instance = binding.factory(this) as T;
        break;
      case 'class':
        instance = this.instantiateClass(binding.implementation as Class<T>);
        break;
      default:
        throw new Error(`Unknown binding type for symbol: ${String(token)}`);
    }

    this.instances.set(token, instance);
    return instance;
  }

  public isBound(token: symbol): boolean {
    return this.bindings.has(token);
  }

  private instantiateClass<T>(implementation: Class<T>): T {
    return new implementation();
  }

  public resolve<T>(implementation: Class<T>): T {
    return new implementation();
  }

  public clear(): void {
    this.bindings.clear();
    this.instances.clear();
  }
}

export const createContainer = (): Container => {
  return new Container();
};
