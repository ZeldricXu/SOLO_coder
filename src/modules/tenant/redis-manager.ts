import Redis from 'ioredis';
import { config } from '@config/index';
import { logger } from '@utils/logger';

class RedisManager {
  private clients: Map<string, Redis> = new Map();
  private defaultClient: Redis | null = null;

  getDefaultClient(): Redis {
    if (!this.defaultClient) {
      this.defaultClient = this.createClient('default');
    }
    return this.defaultClient;
  }

  getTenantClient(tenantId: string): Redis {
    const key = `tenant:${tenantId}`;
    if (!this.clients.has(key)) {
      this.clients.set(key, this.createClient(key));
    }
    return this.clients.get(key)!;
  }

  private createClient(name: string): Redis {
    logger.debug(`Creating Redis client: ${name}`);

    if (config.redisCluster) {
      const nodes = config.redisUrl.split(',').map(url => {
        const [host, port] = url.replace('redis://', '').split(':');
        return { host, port: parseInt(port, 10) };
      });
      return new Redis.Cluster(nodes, {
        redisOptions: {
          maxRetriesPerRequest: 3,
          enableReadyCheck: true,
        },
      });
    }

    return new Redis(config.redisUrl, {
      maxRetriesPerRequest: 3,
      enableReadyCheck: true,
      reconnectOnError: (err) => {
        const targetError = 'READONLY';
        if (err.message.includes(targetError)) {
          return true;
        }
        return false;
      },
    });
  }

  async disconnectAll(): Promise<void> {
    logger.info('Disconnecting all Redis clients');

    if (this.defaultClient) {
      await this.defaultClient.quit();
      this.defaultClient = null;
    }

    for (const [key, client] of this.clients) {
      logger.debug(`Disconnecting Redis client: ${key}`);
      await client.quit();
    }
    this.clients.clear();
  }
}

export const redisManager = new RedisManager();
