import fs from 'fs';
import path from 'path';
import yaml from 'js-yaml';
import axios from 'axios';
import { ConfigSource, ConfigValue } from './types';
import { getFileModifiedTime } from '../common/file-utils';

export interface ConfigSourceLoader {
  load(): Promise<Record<string, ConfigValue>>;
  watch?(callback: (config: Record<string, ConfigValue>) => void): void;
}

export class EnvSourceLoader implements ConfigSourceLoader {
  private readonly prefix: string;

  constructor(prefix: string = 'APP_') {
    this.prefix = prefix;
  }

  async load(): Promise<Record<string, ConfigValue>> {
    const config: Record<string, ConfigValue> = {};
    for (const [key, value] of Object.entries(process.env)) {
      if (key.startsWith(this.prefix)) {
        const configKey = this.formatConfigKey(key);
        config[configKey] = this.parseValue(value);
      }
    }
    return config;
  }

  private formatConfigKey(envKey: string): string {
    return envKey
      .slice(this.prefix.length)
      .toLowerCase()
      .replace(/_/g, '.');
  }

  private parseValue(value: string): ConfigValue {
    if (value === 'true') return true;
    if (value === 'false') return false;
    if (value === 'null') return null;
    if (!isNaN(Number(value)) && value !== '') return Number(value);
    try {
      return JSON.parse(value);
    } catch {
      return value;
    }
  }
}

export class FileSourceLoader implements ConfigSourceLoader {
  private lastModifiedTime: number = 0;
  private readonly filePath: string;

  constructor(filePath: string) {
    this.filePath = filePath;
  }

  async load(): Promise<Record<string, ConfigValue>> {
    if (!fs.existsSync(this.filePath)) {
      return {};
    }

    const mtime = getFileModifiedTime(this.filePath);
    if (mtime) {
      this.lastModifiedTime = mtime;
    }

    const content = fs.readFileSync(this.filePath, 'utf8');
    return this.parseContent(content, this.filePath);
  }

  private parseContent(content: string, filePath: string): Record<string, ConfigValue> {
    const ext = path.extname(filePath).toLowerCase();

    switch (ext) {
      case '.json':
        return JSON.parse(content);
      case '.yaml':
      case '.yml':
        return yaml.load(content) as Record<string, ConfigValue>;
      default:
        return {};
    }
  }

  watch(callback: (config: Record<string, ConfigValue>) => void): void {
    fs.watch(this.filePath, async () => {
      const mtime = getFileModifiedTime(this.filePath);
      if (mtime && mtime > this.lastModifiedTime) {
        const config = await this.load();
        callback(config);
      }
    });
  }
}

export class RemoteSourceLoader implements ConfigSourceLoader {
  private lastEtag?: string;
  private readonly url: string;
  private readonly headers?: Record<string, string>;

  constructor(url: string, headers?: Record<string, string>) {
    this.url = url;
    this.headers = headers;
  }

  async load(): Promise<Record<string, ConfigValue>> {
    try {
      const response = await axios.get(this.url, {
        headers: this.headers,
      });
      this.lastEtag = response.headers['etag'];
      return response.data;
    } catch {
      return {};
    }
  }

  watch(callback: (config: Record<string, ConfigValue>) => void): void {
    const watchInterval = setInterval(async () => {
      try {
        const response = await axios.get(this.url, {
          headers: {
            ...this.headers,
            'If-None-Match': this.lastEtag,
          },
        });
        if (response.status === 200 && response.data) {
          this.lastEtag = response.headers['etag'];
          callback(response.data);
        }
      } catch {
        // Ignore errors during watch
      }
    }, 30000);

    // Store interval ID for potential cleanup (not implemented here for backward compatibility)
  }
}

export function createSourceLoader(source: ConfigSource): ConfigSourceLoader {
  switch (source.type) {
    case 'env':
      return new EnvSourceLoader(source.options.prefix as string);
    case 'file':
      return new FileSourceLoader(source.options.path as string);
    case 'remote':
      return new RemoteSourceLoader(
        source.options.url as string,
        source.options.headers as Record<string, string>
      );
    default:
      return { load: async () => ({}) };
  }
}
