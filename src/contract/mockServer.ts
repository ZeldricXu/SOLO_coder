import { OpenAPISchema, GraphQLSchema, MockEndpoint, MockServerConfig } from './types';
import { logger, delay } from '../utils/common';
import { openAPIMockGenerator } from './generators/OpenAPIMockGenerator';
import { graphQLMockGenerator } from './generators/GraphQLMockGenerator';
import express, { Express, Request, Response } from 'express';

export class MockServerGenerator {
  generateFromOpenAPI(schema: OpenAPISchema, config: Partial<MockServerConfig> = {}): MockServerConfig {
    return openAPIMockGenerator.generate(schema, config);
  }

  generateFromGraphQL(schema: GraphQLSchema, config: Partial<MockServerConfig> = {}): MockServerConfig {
    return graphQLMockGenerator.generate(schema, config);
  }
}

export class MockServer {
  private app: Express;
  private config: MockServerConfig;
  private server: any;
  private delayMs: number = 0;
  private errorRate: number = 0;

  constructor(config: MockServerConfig) {
    this.config = config;
    this.app = express();
    this.app.use(express.json());
    this.setupRoutes();
  }

  private setupRoutes(): void {
    for (const endpoint of this.config.endpoints) {
      const method = endpoint.method.toLowerCase() as keyof Express;
      (this.app as any)[method](endpoint.path, async (req: Request, res: Response) => {
        if (this.delayMs > 0) {
          await delay(this.delayMs);
        }

        if (Math.random() < this.errorRate) {
          res.status(500).json({ error: 'Mock server error' });
          return;
        }

        res.status(endpoint.statusCode).json(endpoint.response);
      });
    }

    this.app.all('*', (req: Request, res: Response) => {
      res.status(this.config.defaultStatusCode || 404).json(this.config.defaultResponse || { error: 'Not found' });
    });
  }

  setDelay(delayMs: number): void {
    this.delayMs = delayMs;
  }

  setErrorRate(rate: number): void {
    this.errorRate = Math.max(0, Math.min(1, rate));
  }

  async start(): Promise<void> {
    return new Promise((resolve) => {
      this.server = this.app.listen(this.config.port, () => {
        logger.info(`Mock server started on port ${this.config.port}`);
        resolve();
      });
    });
  }

  async stop(): Promise<void> {
    if (this.server) {
      return new Promise((resolve, reject) => {
        this.server.close((err: Error) => {
          if (err) reject(err);
          else {
            logger.info(`Mock server stopped`);
            resolve();
          }
        });
      });
    }
  }

  getApp(): Express {
    return this.app;
  }
}

export const mockServerGenerator = new MockServerGenerator();
