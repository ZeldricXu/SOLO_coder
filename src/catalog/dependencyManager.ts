import { Dependency, DependencyGraph, ServiceMetadata } from './types';
import { ServiceCatalog, serviceCatalog } from './serviceCatalog';
import { generateId, currentDateTime, logger } from '../utils/common';

export class DependencyManager {
  private dependencies: Map<string, Dependency> = new Map();
  private sourceIndex: Map<string, Dependency[]> = new Map();
  private targetIndex: Map<string, Dependency[]> = new Map();
  private catalog: ServiceCatalog;

  constructor() {
    this.catalog = serviceCatalog;
  }

  addDependency(
    sourceServiceId: string,
    targetServiceId: string,
    relationship: Dependency['relationship'] = 'depends_on',
    options: Partial<Dependency> = {}
  ): Dependency {
    const dependency: Dependency = {
      ...options,
      dependencyId: generateId('dep_'),
      sourceServiceId,
      targetServiceId,
      relationship,
      isCritical: options.isCritical || false,
      createdAt: currentDateTime(),
    };

    this.dependencies.set(dependency.dependencyId, dependency);

    const sourceDeps = this.sourceIndex.get(sourceServiceId) || [];
    sourceDeps.push(dependency);
    this.sourceIndex.set(sourceServiceId, sourceDeps);

    const targetDeps = this.targetIndex.get(targetServiceId) || [];
    targetDeps.push(dependency);
    this.targetIndex.set(targetServiceId, targetDeps);

    logger.debug(`Dependency added`, {
      dependencyId: dependency.dependencyId,
      source: sourceServiceId,
      target: targetServiceId,
      relationship,
    });

    return dependency;
  }

  getDependency(dependencyId: string): Dependency | undefined {
    return this.dependencies.get(dependencyId);
  }

  removeDependency(dependencyId: string): boolean {
    const dependency = this.dependencies.get(dependencyId);
    if (!dependency) return false;

    const sourceDeps = this.sourceIndex.get(dependency.sourceServiceId) || [];
    this.sourceIndex.set(
      dependency.sourceServiceId,
      sourceDeps.filter(d => d.dependencyId !== dependencyId)
    );

    const targetDeps = this.targetIndex.get(dependency.targetServiceId) || [];
    this.targetIndex.set(
      dependency.targetServiceId,
      targetDeps.filter(d => d.dependencyId !== dependencyId)
    );

    return this.dependencies.delete(dependencyId);
  }

  getOutgoingDependencies(serviceId: string): Dependency[] {
    return this.sourceIndex.get(serviceId) || [];
  }

  getIncomingDependencies(serviceId: string): Dependency[] {
    return this.targetIndex.get(serviceId) || [];
  }

  getAllDependencies(serviceId: string): Dependency[] {
    return [
      ...this.getOutgoingDependencies(serviceId),
      ...this.getIncomingDependencies(serviceId),
    ];
  }

  listAllDependencies(): Dependency[] {
    return Array.from(this.dependencies.values());
  }

  buildDependencyGraph(serviceId?: string, maxDepth: number = 3): DependencyGraph {
    const nodes: DependencyGraph['nodes'] = [];
    const edges: DependencyGraph['edges'] = [];
    const visited = new Set<string>();

    const traverse = (currentServiceId: string, depth: number) => {
      if (depth > maxDepth || visited.has(currentServiceId)) return;
      visited.add(currentServiceId);

      const service = this.catalog.getService(currentServiceId);
      if (service) {
        nodes.push({
          id: service.serviceId,
          name: service.name,
          type: service.type,
          status: service.status,
          version: service.version,
        });
      }

      const outgoing = this.getOutgoingDependencies(currentServiceId);
      for (const dep of outgoing) {
        edges.push({
          source: dep.sourceServiceId,
          target: dep.targetServiceId,
          relationship: dep.relationship,
          isCritical: dep.isCritical,
        });
        traverse(dep.targetServiceId, depth + 1);
      }
    };

    if (serviceId) {
      traverse(serviceId, 0);
    } else {
      const services = this.catalog.listServices();
      for (const service of services) {
        traverse(service.serviceId, 0);
      }
    }

    return { nodes, edges };
  }

  findDependencyPath(
    sourceServiceId: string,
    targetServiceId: string
  ): Dependency[] | null {
    const visited = new Set<string>();
    const path: Dependency[] = [];

    const dfs = (currentId: string): boolean => {
      if (currentId === targetServiceId) return true;
      if (visited.has(currentId)) return false;

      visited.add(currentId);

      const outgoing = this.getOutgoingDependencies(currentId);
      for (const dep of outgoing) {
        path.push(dep);
        if (dfs(dep.targetServiceId)) {
          return true;
        }
        path.pop();
      }

      return false;
    };

    if (dfs(sourceServiceId)) {
      return path;
    }

    return null;
  }

  getDependentServices(serviceId: string): ServiceMetadata[] {
    const dependencies = this.getIncomingDependencies(serviceId);
    return dependencies
      .map(d => this.catalog.getService(d.sourceServiceId))
      .filter((s): s is ServiceMetadata => s !== undefined);
  }

  getDependencyCount(serviceId: string): {
    outgoing: number;
    incoming: number;
    critical: number;
  } {
    const outgoing = this.getOutgoingDependencies(serviceId);
    const incoming = this.getIncomingDependencies(serviceId);
    const all = [...outgoing, ...incoming];
    const critical = all.filter(d => d.isCritical).length;

    return {
      outgoing: outgoing.length,
      incoming: incoming.length,
      critical,
    };
  }

  getTopLevelServices(): ServiceMetadata[] {
    const allServices = this.catalog.listServices();
    return allServices.filter(s => {
      const incoming = this.getIncomingDependencies(s.serviceId);
      return incoming.length === 0;
    });
  }

  getLeafServices(): ServiceMetadata[] {
    const allServices = this.catalog.listServices();
    return allServices.filter(s => {
      const outgoing = this.getOutgoingDependencies(s.serviceId);
      return outgoing.length === 0;
    });
  }
}

export const dependencyManager = new DependencyManager();
