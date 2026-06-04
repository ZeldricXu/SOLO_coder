import * as http from 'http';
import { AddressInfo } from 'net';

export interface WebhookRequest {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: any;
  timestamp: Date;
}

export class WebhookTestServer {
  private server: http.Server;
  private requests: WebhookRequest[] = [];
  private port: number;
  private responseStatus: number = 200;
  private responseDelay: number = 0;

  constructor() {
    this.server = http.createServer(async (req, res) => {
      const chunks: Buffer[] = [];
      
      for await (const chunk of req) {
        chunks.push(chunk);
      }
      
      const body = Buffer.concat(chunks).toString();
      
      const headers: Record<string, string> = {};
      for (const [key, value] of Object.entries(req.headers)) {
        if (typeof value === 'string') {
          headers[key] = value;
        } else if (Array.isArray(value)) {
          headers[key] = value.join(', ');
        }
      }
      
      this.requests.push({
        method: req.method || 'GET',
        url: req.url || '/',
        headers,
        body: body ? JSON.parse(body) : null,
        timestamp: new Date(),
      });
      
      if (this.responseDelay > 0) {
        await new Promise(resolve => setTimeout(resolve, this.responseDelay));
      }
      
      res.writeHead(this.responseStatus, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: this.responseStatus < 400 }));
    });
  }

  async start(): Promise<string> {
    return new Promise((resolve, reject) => {
      this.server.listen(0, '127.0.0.1', () => {
        const address = this.server.address() as AddressInfo;
        this.port = address.port;
        resolve(`http://127.0.0.1:${this.port}`);
      });
      
      this.server.on('error', reject);
    });
  }

  async stop(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.server.close((err) => {
        if (err) reject(err);
        else resolve();
      });
    });
  }

  setResponseStatus(status: number): void {
    this.responseStatus = status;
  }

  setResponseDelay(ms: number): void {
    this.responseDelay = ms;
  }

  getRequests(): WebhookRequest[] {
    return [...this.requests];
  }

  getLastRequest(): WebhookRequest | undefined {
    return this.requests[this.requests.length - 1];
  }

  getRequestCount(): number {
    return this.requests.length;
  }

  clearRequests(): void {
    this.requests = [];
  }

  getPort(): number {
    return this.port;
  }
}
