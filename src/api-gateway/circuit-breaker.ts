export type CircuitState = 'closed' | 'open' | 'half_open';

export interface CircuitBreakerConfig {
  failureThreshold: number;
  timeout: number;
  recoveryTimeout: number;
}

export class CircuitBreaker {
  private state: CircuitState = 'closed';
  private failureCount = 0;
  private lastFailureTime = 0;
  private readonly config: Required<CircuitBreakerConfig>;

  constructor(config: Partial<CircuitBreakerConfig> = {}) {
    this.config = {
      failureThreshold: 5,
      timeout: 30000,
      recoveryTimeout: 60000,
      ...config,
    };
  }

  async execute<T>(fn: () => Promise<T>): Promise<T> {
    if (this.state === 'open') {
      if (this.shouldAttemptRecovery()) {
        this.state = 'half_open';
      } else {
        throw new Error('Circuit breaker is open');
      }
    }

    try {
      const result = await this.withTimeout(fn);
      this.handleSuccess();
      return result;
    } catch (error) {
      this.handleFailure();
      throw error;
    }
  }

  private async withTimeout<T>(fn: () => Promise<T>): Promise<T> {
    return Promise.race([
      fn(),
      new Promise<never>((_, reject) => {
        setTimeout(() => reject(new Error('Operation timed out')), this.config.timeout);
      }),
    ]);
  }

  private shouldAttemptRecovery(): boolean {
    return Date.now() - this.lastFailureTime > this.config.recoveryTimeout;
  }

  private handleSuccess(): void {
    if (this.state === 'half_open') {
      this.state = 'closed';
      this.failureCount = 0;
    }
  }

  private handleFailure(): void {
    this.failureCount++;
    this.lastFailureTime = Date.now();

    if (this.state === 'half_open' || this.failureCount >= this.config.failureThreshold) {
      this.state = 'open';
    }
  }

  getState(): CircuitState {
    return this.state;
  }

  reset(): void {
    this.state = 'closed';
    this.failureCount = 0;
    this.lastFailureTime = 0;
  }
}
