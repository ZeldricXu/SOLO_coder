import axios, { AxiosInstance, AxiosRequestConfig, Method } from 'axios';
import { BaseConnector, QueryResult, SchemaTable } from './base.connector';

interface HttpApiConfig {
  url: string;
  method?: string;
  headers?: Record<string, string>;
  body?: Record<string, any>;
  queryTimeout?: number;
}

export class HttpApiConnector extends BaseConnector {
  private axiosInstance: AxiosInstance | null = null;
  private readonly config: HttpApiConfig;

  constructor(config: HttpApiConfig) {
    super();
    this.config = config;
  }

  async connect(): Promise<void> {
    this.axiosInstance = axios.create({
      baseURL: this.config.url,
      headers: this.config.headers,
      timeout: this.config.queryTimeout ?? 30000,
    });
  }

  async query(sql: string, params?: any[]): Promise<QueryResult> {
    if (!this.axiosInstance) {
      throw new Error('HTTP API connector not connected');
    }
    const method = (this.config.method ?? 'GET').toUpperCase() as Method;
    const requestConfig: AxiosRequestConfig = {
      method,
      url: sql,
    };
    if (method === 'GET' && params) {
      requestConfig.params = params[0];
    } else if (method === 'POST') {
      requestConfig.data = params?.[0] ?? this.config.body;
    }
    const response = await this.axiosInstance.request(requestConfig);
    const data = response.data;
    const rows = Array.isArray(data) ? data : [data];
    return {
      rows,
      rowCount: rows.length,
    };
  }

  async testConnection(): Promise<boolean> {
    if (!this.axiosInstance) {
      await this.connect();
    }
    try {
      const response = await this.axiosInstance!.request({
        method: (this.config.method ?? 'GET').toUpperCase() as Method,
        url: '/',
      });
      return response.status >= 200 && response.status < 400;
    } catch (error) {
      if (axios.isAxiosError(error)) {
        return !!error.response;
      }
      return false;
    }
  }

  async inferSchema(): Promise<SchemaTable[]> {
    if (!this.axiosInstance) {
      throw new Error('HTTP API connector not connected');
    }
    try {
      const method = (this.config.method ?? 'GET').toUpperCase() as Method;
      const response = await this.axiosInstance.request({
        method,
        url: '/',
        ...(method === 'POST' ? { data: this.config.body } : {}),
      });
      const data = response.data;
      if (!data || typeof data !== 'object') {
        return [];
      }
      const sample = Array.isArray(data) ? data[0] : data;
      if (!sample || typeof sample !== 'object') {
        return [];
      }
      const columns = Object.entries(sample).map(([key, value]) => ({
        name: key,
        type: typeof value === 'object' ? (value === null ? 'null' : Array.isArray(value) ? 'array' : 'object') : typeof value,
        nullable: value === null,
      }));
      return [{ table: this.config.url, columns }];
    } catch {
      return [];
    }
  }

  async disconnect(): Promise<void> {
    this.axiosInstance = null;
  }
}
