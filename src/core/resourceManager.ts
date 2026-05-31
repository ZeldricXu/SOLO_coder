import { Resource, ResourcePool } from './types';
import { generateId, logger } from '../utils/common';

export class ResourceManager {
  private pools: Map<string, ResourcePool> = new Map();

  createPool(poolId: string, maxSize: number, resourceType: string): ResourcePool {
    const pool: ResourcePool = {
      poolId,
      maxSize,
      resources: [],
    };

    for (let i = 0; i < maxSize; i++) {
      pool.resources.push({
        id: generateId('res_'),
        type: resourceType,
        status: 'available',
        metadata: {},
      });
    }

    this.pools.set(poolId, pool);
    logger.info(`Resource pool created`, { poolId, maxSize, resourceType });
    return pool;
  }

  acquireResource(poolId: string): Resource | null {
    const pool = this.pools.get(poolId);
    if (!pool) {
      logger.warn(`Resource pool not found`, { poolId });
      return null;
    }

    const available = pool.resources.find(r => r.status === 'available');
    if (!available) {
      logger.warn(`No available resources in pool`, { poolId });
      return null;
    }

    available.status = 'acquired';
    available.acquiredAt = Date.now();
    logger.debug(`Resource acquired`, { poolId, resourceId: available.id });
    return available;
  }

  releaseResource(poolId: string, resourceId: string): boolean {
    const pool = this.pools.get(poolId);
    if (!pool) return false;

    const resource = pool.resources.find(r => r.id === resourceId);
    if (!resource) return false;

    resource.status = 'released';
    delete resource.acquiredAt;
    logger.debug(`Resource released`, { poolId, resourceId });
    return true;
  }

  getPoolStatus(poolId: string) {
    const pool = this.pools.get(poolId);
    if (!pool) return null;

    const available = pool.resources.filter(r => r.status === 'available').length;
    const acquired = pool.resources.filter(r => r.status === 'acquired').length;

    return {
      poolId,
      maxSize: pool.maxSize,
      available,
      acquired,
      utilization: acquired / pool.maxSize,
    };
  }
}

export const resourceManager = new ResourceManager();
