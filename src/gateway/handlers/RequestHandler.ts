import { Route, ProxyRequest, ProxyResponse } from '../types';
import { IRequestHandler, IHttpClient, IGatewayMiddleware, GatewayHandlerOptions } from '../interfaces';
import { httpClient } from './HttpClient';
import { logger, withTimeout } from '../../utils/common';

export class HttpRequestHandler implements IRequestHandler {
  private httpClient: IHttpClient;
  private middlewares: IGatewayMiddleware[] = [];
  private options: GatewayHandlerOptions;

  constructor(client?: IHttpClient, options?: GatewayHandlerOptions) {
    this.httpClient = client || httpClient;
    this.options = {
      timeoutMs: 60000,
      retries: 0,
      ...options,
    };
  }

  canHandle(route: Route): boolean {
    return route.protocol === 'http' || route.protocol === 'graphql';
  }

  async handle(request: ProxyRequest, route: Route): Promise<ProxyResponse> {
    const timeoutMs = route.timeoutMs || this.options.timeoutMs || 60000;

    try {
      return await withTimeout(
        this.handleWithMiddleware(request, route),
        timeoutMs,
        `Request timed out after ${timeoutMs}ms`
      );
    } catch (error) {
      if (error instanceof Error && error.message.includes('timed out')) {
        logger.error(`Request timeout`, {
          path: request.path,
          method: request.method,
          timeoutMs,
        });
        return {
          status: 504,
          headers: { 'content-type': 'application/json' },
          body: {
            error: 'Gateway Timeout',
            message: error.message,
          },
          latencyMs: timeoutMs,
        };
      }
      throw error;
    }
  }

  private async handleWithMiddleware(
    request: ProxyRequest,
    route: Route
  ): Promise<ProxyResponse> {
    let processedRequest = request;

    for (const middleware of this.middlewares) {
      if (middleware.preRequest) {
        try {
          processedRequest = await middleware.preRequest(processedRequest, route);
        } catch (error) {
          logger.warn(`Middleware ${middleware.name} preRequest failed`, {
            error: error instanceof Error ? error.message : 'Unknown error',
          });
        }
      }
    }

    try {
      let response = await this.executeWithRetry(processedRequest, route);

      for (const middleware of this.middlewares) {
        if (middleware.postRequest) {
          try {
            response = await middleware.postRequest(response, route);
          } catch (error) {
            logger.warn(`Middleware ${middleware.name} postRequest failed`, {
              error: error instanceof Error ? error.message : 'Unknown error',
            });
          }
        }
      }

      return response;
    } catch (error) {
      for (const middleware of this.middlewares) {
        if (middleware.onError) {
          try {
            return await middleware.onError(error as Error, processedRequest, route);
          } catch (e) {
            logger.warn(`Middleware ${middleware.name} onError failed`, {
              error: e instanceof Error ? e.message : 'Unknown error',
            });
          }
        }
      }
      throw error;
    }
  }

  private async executeWithRetry(
    request: ProxyRequest,
    route: Route
  ): Promise<ProxyResponse> {
    const maxRetries = this.options.retries || 0;
    let lastError: Error | null = null;

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return await this.httpClient.execute(request, route);
      } catch (error) {
        lastError = error instanceof Error ? error : new Error('Unknown error');
        if (attempt < maxRetries) {
          logger.warn(`Request attempt ${attempt + 1} failed, retrying`, {
            path: request.path,
            method: request.method,
            error: lastError.message,
            attempt: attempt + 1,
            maxRetries,
          });
          await this.delay(100 * Math.pow(2, attempt));
        }
      }
    }

    throw lastError || new Error('Request failed after retries');
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  use(middleware: IGatewayMiddleware): void {
    this.middlewares.push(middleware);
    logger.info(`Gateway middleware registered`, { name: middleware.name });
  }

  removeMiddleware(name: string): boolean {
    const index = this.middlewares.findIndex(m => m.name === name);
    if (index !== -1) {
      this.middlewares.splice(index, 1);
      return true;
    }
    return false;
  }

  getMiddlewares(): IGatewayMiddleware[] {
    return [...this.middlewares];
  }

  setOptions(options: Partial<GatewayHandlerOptions>): void {
    this.options = { ...this.options, ...options };
    logger.info(`Request handler options updated`, { options: this.options });
  }

  getOptions(): GatewayHandlerOptions {
    return { ...this.options };
  }
}

export const httpRequestHandler = new HttpRequestHandler();
