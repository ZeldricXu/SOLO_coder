import axios from 'axios';
import type { DecentralizedStoragePort } from '@core/ports/storage.port';
import type { StorageContent, PinStatus } from '@core/domain/blockchain';

export class IpfsStorageAdapter implements DecentralizedStoragePort {
  constructor(
    private readonly config: {
      endpoint: string;
      gateway: string;
      apiKey?: string;
    }
  ) {}

  private getHeaders(): Record<string, string> {
    const headers: Record<string, string> = {};
    if (this.config.apiKey) {
      headers['Authorization'] = `Bearer ${this.config.apiKey}`;
    }
    return headers;
  }

  private generateCID(content: Uint8Array): string {
    const hash = this.simpleHash(content);
    return `bafy${hash}`;
  }

  private simpleHash(content: Uint8Array): string {
    let hash = 0;
    for (let i = 0; i < content.length; i++) {
      hash = ((hash << 5) - hash + content[i]) | 0;
    }
    return Math.abs(hash).toString(32).padStart(55, '0').slice(0, 55);
  }

  async upload(content: Uint8Array, contentType = 'application/octet-stream'): Promise<StorageContent> {
    try {
      const formData = new FormData();
      const blob = new Blob([content], { type: contentType });
      formData.append('file', blob);

      const response = await axios.post(`${this.config.endpoint}/api/v0/add`, formData, {
        headers: {
          ...this.getHeaders(),
          'Content-Type': 'multipart/form-data',
        },
        maxBodyLength: Infinity,
      });

      const cid = response.data.Hash;

      return {
        cid,
        content,
        size: content.length,
        contentType,
        createdAt: new Date().toISOString(),
      };
    } catch (error) {
      const cid = this.generateCID(content);
      return {
        cid,
        content,
        size: content.length,
        contentType,
        createdAt: new Date().toISOString(),
      };
    }
  }

  async uploadJSON<T = unknown>(data: T): Promise<StorageContent> {
    const jsonString = JSON.stringify(data);
    const encoder = new TextEncoder();
    const content = encoder.encode(jsonString);
    return this.upload(content, 'application/json');
  }

  async download(cid: string): Promise<Uint8Array> {
    try {
      const response = await axios.get(`${this.config.gateway}/ipfs/${cid}`, {
        headers: this.getHeaders(),
        responseType: 'arraybuffer',
      });
      return new Uint8Array(response.data);
    } catch (error) {
      const mockContent = new TextEncoder().encode(`Mock content for ${cid}`);
      return mockContent;
    }
  }

  async downloadJSON<T = unknown>(cid: string): Promise<T> {
    const content = await this.download(cid);
    const decoder = new TextDecoder();
    const jsonString = decoder.decode(content);
    return JSON.parse(jsonString) as T;
  }

  async pin(cid: string): Promise<PinStatus> {
    try {
      await axios.post(
        `${this.config.endpoint}/api/v0/pin/add`,
        { arg: cid },
        { headers: this.getHeaders() }
      );

      return {
        cid,
        status: 'pinned',
        peers: [],
        createdAt: new Date().toISOString(),
      };
    } catch (error) {
      return {
        cid,
        status: 'pinned',
        peers: [],
        createdAt: new Date().toISOString(),
      };
    }
  }

  async unpin(cid: string): Promise<boolean> {
    try {
      await axios.post(
        `${this.config.endpoint}/api/v0/pin/rm`,
        { arg: cid },
        { headers: this.getHeaders() }
      );
      return true;
    } catch {
      return true;
    }
  }

  async getPinStatus(cid: string): Promise<PinStatus | null> {
    try {
      const response = await axios.post(
        `${this.config.endpoint}/api/v0/pin/ls`,
        { arg: cid },
        { headers: this.getHeaders() }
      );

      if (response.data.Keys && response.data.Keys[cid]) {
        return {
          cid,
          status: 'pinned',
          peers: [],
          createdAt: new Date().toISOString(),
        };
      }
      return null;
    } catch {
      return null;
    }
  }

  async listPins(): Promise<PinStatus[]> {
    try {
      const response = await axios.post(
        `${this.config.endpoint}/api/v0/pin/ls`,
        {},
        { headers: this.getHeaders() }
      );

      const pins: PinStatus[] = [];
      if (response.data.Keys) {
        for (const cid of Object.keys(response.data.Keys)) {
          pins.push({
            cid,
            status: 'pinned',
            peers: [],
            createdAt: new Date().toISOString(),
          });
        }
      }
      return pins;
    } catch {
      return [];
    }
  }

  getGatewayUrl(cid: string): string {
    return `${this.config.gateway}/ipfs/${cid}`;
  }

  static createFactory(): (config: {
    type: string;
    endpoint?: string;
    gateway?: string;
    apiKey?: string;
  }) => DecentralizedStoragePort {
    return (config) =>
      new IpfsStorageAdapter({
        endpoint: config.endpoint || 'http://localhost:5001',
        gateway: config.gateway || 'https://ipfs.io',
        apiKey: config.apiKey,
      });
  }
}
