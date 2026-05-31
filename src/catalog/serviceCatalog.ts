import { ServiceMetadata, ServiceSearchQuery, DependencyGraph, ServiceHealth } from './types';
import { generateId, currentDateTime, logger } from '../utils/common';

export class ServiceCatalog {
  private services: Map<string, ServiceMetadata> = new Map();
  private nameIndex: Map<string, string> = new Map();
  private tagIndex: Map<string, string[]> = new Map();
  private teamIndex: Map<string, string[]> = new Map();

  registerService(service: Omit<ServiceMetadata, 'serviceId' | 'createdAt' | 'updatedAt'>): ServiceMetadata {
    const now = currentDateTime();
    const serviceId = generateId('svc_');
    const fullService: ServiceMetadata = {
      ...service,
      serviceId,
      createdAt: now,
      updatedAt: now,
    } as ServiceMetadata;

    this.services.set(serviceId, fullService);
    this.nameIndex.set(fullService.name, serviceId);

    for (const tag of fullService.tags) {
      const existing = this.tagIndex.get(tag) || [];
      if (!existing.includes(serviceId)) {
        existing.push(serviceId);
        this.tagIndex.set(tag, existing);
      }
    }

    if (fullService.team) {
      const existing = this.teamIndex.get(fullService.team) || [];
      if (!existing.includes(serviceId)) {
        existing.push(serviceId);
        this.teamIndex.set(fullService.team, existing);
      }
    }

    logger.info(`Service registered`, { serviceId, name: service.name });
    return fullService;
  }

  getService(serviceId: string): ServiceMetadata | undefined {
    return this.services.get(serviceId);
  }

  getServiceByName(name: string): ServiceMetadata | undefined {
    const id = this.nameIndex.get(name);
    return id ? this.services.get(id) : undefined;
  }

  updateService(serviceId: string, updates: Partial<ServiceMetadata>): ServiceMetadata | undefined {
    const service = this.services.get(serviceId);
    if (!service) return undefined;

    if (updates.name && updates.name !== service.name) {
      this.nameIndex.delete(service.name);
      this.nameIndex.set(updates.name, serviceId);
    }

    if (updates.tags) {
      for (const tag of service.tags) {
        const existing = this.tagIndex.get(tag) || [];
        const filtered = existing.filter(id => id !== serviceId);
        if (filtered.length === 0) {
          this.tagIndex.delete(tag);
        } else {
          this.tagIndex.set(tag, filtered);
        }
      }
      for (const tag of updates.tags) {
        const existing = this.tagIndex.get(tag) || [];
        if (!existing.includes(serviceId)) {
          existing.push(serviceId);
          this.tagIndex.set(tag, existing);
        }
      }
    }

    if (updates.team && updates.team !== service.team) {
      if (service.team) {
        const existing = this.teamIndex.get(service.team) || [];
        const filtered = existing.filter(id => id !== serviceId);
        if (filtered.length === 0) {
          this.teamIndex.delete(service.team);
        } else {
          this.teamIndex.set(service.team, filtered);
        }
      }
      const existing = this.teamIndex.get(updates.team) || [];
      if (!existing.includes(serviceId)) {
        existing.push(serviceId);
        this.teamIndex.set(updates.team, existing);
      }
    }

    const updated: ServiceMetadata = {
      ...service,
      ...updates,
      updatedAt: currentDateTime(),
    };

    this.services.set(serviceId, updated);
    logger.info(`Service updated`, { serviceId });
    return updated;
  }

  deleteService(serviceId: string): boolean {
    const service = this.services.get(serviceId);
    if (!service) return false;

    this.nameIndex.delete(service.name);

    for (const tag of service.tags) {
      const existing = this.tagIndex.get(tag) || [];
      const filtered = existing.filter(id => id !== serviceId);
      if (filtered.length === 0) {
        this.tagIndex.delete(tag);
      } else {
        this.tagIndex.set(tag, filtered);
      }
    }

    if (service.team) {
      const existing = this.teamIndex.get(service.team) || [];
      const filtered = existing.filter(id => id !== serviceId);
      if (filtered.length === 0) {
        this.teamIndex.delete(service.team);
      } else {
        this.teamIndex.set(service.team, filtered);
      }
    }

    return this.services.delete(serviceId);
  }

  search(query: ServiceSearchQuery = {}): { services: ServiceMetadata[]; total: number } {
    let services = Array.from(this.services.values());

    if (query.name) {
      const lowerName = query.name.toLowerCase();
      services = services.filter(s => s.name.toLowerCase().includes(lowerName));
    }

    if (query.type) {
      services = services.filter(s => s.type === query.type);
    }

    if (query.team) {
      services = services.filter(s => s.team === query.team);
    }

    if (query.owner) {
      services = services.filter(s => s.owner === query.owner);
    }

    if (query.tags?.length) {
      services = services.filter(s => query.tags!.every(tag => s.tags.includes(tag)));
    }

    if (query.status) {
      services = services.filter(s => s.status === query.status);
    }

    if (query.lifecycleStage) {
      services = services.filter(s => s.lifecycleStage === query.lifecycleStage);
    }

    const total = services.length;
    const limit = query.limit || 50;
    const offset = query.offset || 0;

    return {
      services: services.slice(offset, offset + limit),
      total,
    };
  }

  listServices(): ServiceMetadata[] {
    return Array.from(this.services.values());
  }

  getTags(): string[] {
    return Array.from(this.tagIndex.keys());
  }

  getTeams(): string[] {
    return Array.from(this.teamIndex.keys());
  }

  getServicesByTag(tag: string): ServiceMetadata[] {
    const ids = this.tagIndex.get(tag) || [];
    return ids.map(id => this.services.get(id)!).filter(Boolean);
  }

  getServicesByTeam(team: string): ServiceMetadata[] {
    const ids = this.teamIndex.get(team) || [];
    return ids.map(id => this.services.get(id)!).filter(Boolean);
  }

  async checkServiceHealth(serviceId: string): Promise<ServiceHealth> {
    const service = this.services.get(serviceId);
    if (!service) {
      return {
        serviceId,
        overall: 'unknown',
        uptime: 0,
        lastChecked: currentDateTime(),
        checks: [],
      };
    }

    const checks: ServiceHealth['checks'] = [];

    for (const endpoint of service.endpoints) {
      if (endpoint.healthCheck) {
        checks.push({
          name: `health-check-${endpoint.name}`,
          status: 'pass',
          lastChecked: currentDateTime(),
        });
      }
    }

    const overall = checks.every(c => c.status === 'pass')
      ? 'healthy'
      : checks.some(c => c.status === 'fail')
        ? 'unhealthy'
        : 'degraded';

    return {
      serviceId,
      overall,
      uptime: Math.floor(Math.random() * 1000000),
      lastChecked: currentDateTime(),
      checks,
    };
  }
}

export const serviceCatalog = new ServiceCatalog();
