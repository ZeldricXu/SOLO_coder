import { DeploymentInfo, ServiceMetadata } from './types';
import { ServiceCatalog, serviceCatalog } from './serviceCatalog';
import { DependencyManager } from './dependencyManager';
import { generateId, currentDateTime, logger } from '../utils/common';

export class DeploymentManager {
  private deployments: Map<string, DeploymentInfo> = new Map();
  private serviceDeployments: Map<string, DeploymentInfo[]> = new Map();
  private catalog: ServiceCatalog;

  constructor() {
    this.catalog = serviceCatalog;
  }

  recordDeployment(
    serviceId: string,
    version: string,
    environment: string,
    options: Partial<DeploymentInfo> = {}
  ): DeploymentInfo {
    const deployment: DeploymentInfo = {
      ...options,
      deploymentId: generateId('dep_'),
      serviceId,
      version,
      environment,
      status: 'deployed',
      lastDeployedAt: currentDateTime(),
    } as DeploymentInfo;

    this.deployments.set(deployment.deploymentId, deployment);

    const serviceDeps = this.serviceDeployments.get(serviceId) || [];
    serviceDeps.push(deployment);
    this.serviceDeployments.set(serviceId, serviceDeps);

    logger.info(`Deployment recorded`, {
      deploymentId: deployment.deploymentId,
      serviceId,
      version,
      environment,
    });

    return deployment;
  }

  getDeployment(deploymentId: string): DeploymentInfo | undefined {
    return this.deployments.get(deploymentId);
  }

  updateDeployment(deploymentId: string, updates: Partial<DeploymentInfo>): DeploymentInfo | undefined {
    const deployment = this.deployments.get(deploymentId);
    if (!deployment) return undefined;

    const updated: DeploymentInfo = { ...deployment, ...updates };
    this.deployments.set(deploymentId, updated);

    const serviceDeps = this.serviceDeployments.get(deployment.serviceId) || [];
    const index = serviceDeps.findIndex(d => d.deploymentId === deploymentId);
    if (index !== -1) {
      serviceDeps[index] = updated;
      this.serviceDeployments.set(deployment.serviceId, serviceDeps);
    }

    return updated;
  }

  getServiceDeployments(serviceId: string, environment?: string): DeploymentInfo[] {
    let deployments = this.serviceDeployments.get(serviceId) || [];
    if (environment) {
      deployments = deployments.filter(d => d.environment === environment);
    }
    return deployments.sort((a, b) =>
      new Date(b.lastDeployedAt).getTime() - new Date(a.lastDeployedAt).getTime()
    );
  }

  getLatestDeployment(serviceId: string, environment?: string): DeploymentInfo | undefined {
    const deployments = this.getServiceDeployments(serviceId, environment);
    return deployments[0];
  }

  getCurrentVersions(): Map<string, Map<string, string>> {
    const result = new Map<string, Map<string, string>>();

    for (const service of this.catalog.listServices()) {
      const envVersions = new Map<string, string>();
      const deployments = this.serviceDeployments.get(service.serviceId) || [];

      for (const deployment of deployments) {
        if (!envVersions.has(deployment.environment)) {
          envVersions.set(deployment.environment, deployment.version);
        }
      }

      if (envVersions.size > 0) {
        result.set(service.serviceId, envVersions);
      }
    }

    return result;
  }

  listDeployments(): DeploymentInfo[] {
    return Array.from(this.deployments.values()).sort((a, b) =>
      new Date(b.lastDeployedAt).getTime() - new Date(a.lastDeployedAt).getTime()
    );
  }

  getDeploymentStats(): {
    totalDeployments: number;
    servicesDeployed: number;
    environments: string[];
    failedDeployments: number;
  } {
    const deployments = Array.from(this.deployments.values());
    const environments = new Set(deployments.map(d => d.environment));
    const services = new Set(deployments.map(d => d.serviceId));
    const failed = deployments.filter(d => d.status === 'failed').length;

    return {
      totalDeployments: deployments.length,
      servicesDeployed: services.size,
      environments: Array.from(environments),
      failedDeployments: failed,
    };
  }
}

export const deploymentManager = new DeploymentManager();

export class SoftwareCatalog {
  private serviceCatalog: ServiceCatalog;
  private dependencyManager: DependencyManager;
  private deploymentManager: DeploymentManager;

  constructor() {
    this.serviceCatalog = serviceCatalog;
    this.dependencyManager = new DependencyManager();
    this.deploymentManager = deploymentManager;
  }

  getServiceCatalog(): ServiceCatalog {
    return this.serviceCatalog;
  }

  getDependencyManager(): DependencyManager {
    return this.dependencyManager;
  }

  getDeploymentManager(): DeploymentManager {
    return this.deploymentManager;
  }

  registerService(
    service: Omit<ServiceMetadata, 'serviceId' | 'createdAt' | 'updatedAt'>
  ): ServiceMetadata {
    return this.serviceCatalog.registerService(service);
  }

  addDependency(
    sourceId: string,
    targetId: string,
    relationship: string = 'depends_on'
  ) {
    return this.dependencyManager.addDependency(sourceId, targetId, relationship as any);
  }

  getServiceWithDependencies(serviceId: string) {
    const service = this.serviceCatalog.getService(serviceId);
    if (!service) return null;

    const outgoing = this.dependencyManager.getOutgoingDependencies(serviceId);
    const incoming = this.dependencyManager.getIncomingDependencies(serviceId);
    const deployments = this.deploymentManager.getServiceDeployments(serviceId);

    return {
      service,
      dependencies: {
        outgoing,
        incoming,
      },
      deployments,
    };
  }

  getFullGraph(maxDepth: number = 3) {
    return this.dependencyManager.buildDependencyGraph(undefined, maxDepth);
  }
}

export const softwareCatalog = new SoftwareCatalog();
