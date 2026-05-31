import { ConfigSource } from '../../types/config';
import { parseJsonSafe } from '../../common/utils';
import { ConfigError } from '../../common/errors';
import * as fs from 'fs';
import * as path from 'path';
import Redis from 'ioredis';

export interface IConfigSource {
  type: string;
  name: string;
  priority: number;
  load(): Promise<Record<string, unknown>>;
  watch?(callback: (config: Record<string, unknown>) => void): void;
  stopWatching?(): void;
}

export class EnvConfigSource implements IConfigSource {
  type = 'env';
  name: string;
  priority: number;
  private prefix: string;

  constructor(name: string = 'env', priority: number = 100, prefix: string = '') {
    this.name = name;
    this.priority = priority;
    this.prefix = prefix;
  }

  async load(): Promise<Record<string, unknown>> {
    const config: Record<string, unknown> = {};

    for (const [key, value] of Object.entries(process.env)) {
      if (this.prefix && !key.startsWith(this.prefix)) {
        continue;
      }

      const configKey = this.prefix
        ? key.substring(this.prefix.length).toLowerCase().replace(/_/g, '.')
        : key.toLowerCase();

      config[configKey] = this.parseValue(value);
    }

    return config;
  }

  private parseValue(value: string): unknown {
    if (value === 'true') return true;
    if (value === 'false') return false;
    if (value === 'null') return null;
    if (value === 'undefined') return undefined;

    const numValue = Number(value);
    if (!isNaN(numValue) && value.trim() !== '') {
      return numValue;
    }

    if (value.startsWith('{') || value.startsWith('[')) {
      const parsed = parseJsonSafe(value, value);
      if (parsed !== value) return parsed;
    }

    return value;
  }
}

export class FileConfigSource implements IConfigSource {
  type = 'file';
  name: string;
  priority: number;
  private filePath: string;
  private watcher?: fs.FSWatcher;

  constructor(filePath: string, name: string = 'file', priority: number = 50) {
    this.filePath = path.resolve(filePath);
    this.name = name;
    this.priority = priority;
  }

  async load(): Promise<Record<string, unknown>> {
    try {
      if (!fs.existsSync(this.filePath)) {
        return {};
      }

      const content = fs.readFileSync(this.filePath, 'utf-8');

      if (this.filePath.endsWith('.json')) {
        return parseJsonSafe(content, {});
      }

      if (this.filePath.endsWith('.yaml') || this.filePath.endsWith('.yml')) {
        return this.parseYaml(content);
      }

      if (this.filePath.endsWith('.env')) {
        return this.parseEnv(content);
      }

      return {};
    } catch (error) {
      throw new ConfigError(`加载配置文件失败: ${this.filePath}`, { error });
    }
  }

  private parseYaml(content: string): Record<string, unknown> {
    const lines = content.split('\n');
    const result: Record<string, unknown> = {};
    const stack: { indent: number; obj: Record<string, unknown> }[] = [{ indent: -1, obj: result }];

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;

      const indent = line.search(/\S/);
      while (stack.length > 1 && stack[stack.length - 1].indent >= indent) {
        stack.pop();
      }

      const parent = stack[stack.length - 1].obj;
      const colonIndex = trimmed.indexOf(':');

      if (colonIndex !== -1) {
        const key = trimmed.substring(0, colonIndex).trim();
        const value = trimmed.substring(colonIndex + 1).trim();

        if (value === '') {
          const newObj: Record<string, unknown> = {};
          parent[key] = newObj;
          stack.push({ indent, obj: newObj });
        } else {
          parent[key] = this.parseValue(value);
        }
      }
    }

    return result;
  }

  private parseEnv(content: string): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    const lines = content.split('\n');

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;

      const equalsIndex = trimmed.indexOf('=');
      if (equalsIndex !== -1) {
        const key = trimmed.substring(0, equalsIndex).trim();
        let value = trimmed.substring(equalsIndex + 1).trim();

        if ((value.startsWith('"') && value.endsWith('"')) ||
            (value.startsWith("'") && value.endsWith("'"))) {
          value = value.substring(1, value.length - 1);
        }

        result[key.toLowerCase()] = this.parseValue(value);
      }
    }

    return result;
  }

  private parseValue(value: string): unknown {
    if (value === 'true') return true;
    if (value === 'false') return false;
    if (value === 'null') return null;

    const numValue = Number(value);
    if (!isNaN(numValue) && value.trim() !== '') {
      return numValue;
    }

    return value;
  }

  watch(callback: (config: Record<string, unknown>) => void): void {
    if (this.watcher) {
      this.watcher.close();
    }

    this.watcher = fs.watch(this.filePath, async (event) => {
      if (event === 'change') {
        try {
          const config = await this.load();
          callback(config);
        } catch (error) {
          console.error('配置文件变化处理失败:', error);
        }
      }
    });
  }

  stopWatching(): void {
    if (this.watcher) {
      this.watcher.close();
      this.watcher = undefined;
    }
  }
}

export class RedisConfigSource implements IConfigSource {
  type = 'redis';
  name: string;
  priority: number;
  private redisClient: Redis;
  private key: string;
  private subscriber?: Redis;

  constructor(redisClient: Redis, key: string, name: string = 'redis', priority: number = 75) {
    this.redisClient = redisClient;
    this.key = key;
    this.name = name;
    this.priority = priority;
  }

  async load(): Promise<Record<string, unknown>> {
    try {
      const value = await this.redisClient.get(this.key);
      if (!value) return {};
      return parseJsonSafe(value, {});
    } catch (error) {
      throw new ConfigError('从Redis加载配置失败', { error });
    }
  }

  watch(callback: (config: Record<string, unknown>) => void): void {
    if (!this.subscriber) {
      this.subscriber = this.redisClient.duplicate();
    }

    this.subscriber.subscribe(`__keyspace@0__:${this.key}`, async (err, count) => {
      if (err) {
        console.error('Redis订阅失败:', err);
        return;
      }
    });

    this.subscriber.on('message', async (channel, message) => {
      if (message === 'set' || message === 'hset') {
        try {
          const config = await this.load();
          callback(config);
        } catch (error) {
          console.error('Redis配置变化处理失败:', error);
        }
      }
    });
  }

  stopWatching(): void {
    if (this.subscriber) {
      this.subscriber.unsubscribe();
      this.subscriber.disconnect();
      this.subscriber = undefined;
    }
  }
}

export class HttpConfigSource implements IConfigSource {
  type = 'http';
  name: string;
  priority: number;
  private url: string;
  private headers?: Record<string, string>;
  private pollInterval?: number;
  private pollTimer?: NodeJS.Timeout;

  constructor(
    url: string,
    headers?: Record<string, string>,
    name: string = 'http',
    priority: number = 60
  ) {
    this.url = url;
    this.headers = headers;
    this.name = name;
    this.priority = priority;
  }

  async load(): Promise<Record<string, unknown>> {
    try {
      const response = await fetch(this.url, {
        headers: this.headers
      });

      if (!response.ok) {
        throw new Error(`HTTP请求失败: ${response.status}`);
      }

      return await response.json();
    } catch (error) {
      throw new ConfigError('从HTTP源加载配置失败', { error });
    }
  }

  watch(callback: (config: Record<string, unknown>) => void, pollIntervalMs: number = 30000): void {
    this.pollInterval = pollIntervalMs;
    this.pollTimer = setInterval(async () => {
      try {
        const config = await this.load();
        callback(config);
      } catch (error) {
        console.error('HTTP配置轮询失败:', error);
      }
    }, pollIntervalMs);
  }

  stopWatching(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = undefined;
    }
  }
}
