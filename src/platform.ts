import { ApiGateway, GatewayConfig } from './api-gateway';
import { OfflineCache, OfflineCacheConfig } from './offline-cache';
import { ConfigManager, ConfigManagerConfig } from './config-manager';
import { MonitoringService, MonitoringConfig } from './monitoring';
import { NotificationService, NotificationConfig } from './notification';
import { StorageManager, StorageConfig } from './storage-manager';
import { DeviceShadowService, DeviceShadowConfig } from './device-shadow';
import { Logger, LoggerConfig } from './logging';
import { EdgeRuleEngine, EdgeRuleConfig } from './edge-rules';
import { OTAUpgradeService, OTAConfig } from './ota-upgrade';
import { CoreEngine, CoreEngineConfig } from './core';

export interface PlatformConfig {
  apiGateway: GatewayConfig;
  offlineCache: Partial<OfflineCacheConfig>;
  configManager: ConfigManagerConfig;
  monitoring: MonitoringConfig;
  notification: NotificationConfig;
  storage: StorageConfig;
  deviceShadow: DeviceShadowConfig;
  logging: Partial<LoggerConfig>;
  edgeRules: EdgeRuleConfig;
  otaUpgrade: OTAConfig;
  coreEngine: CoreEngineConfig;
}

export class CloudNativePlatform {
  public apiGateway: ApiGateway;
  public offlineCache: OfflineCache;
  public configManager: ConfigManager;
  public monitoring: MonitoringService;
  public notification: NotificationService;
  public storage: StorageManager;
  public deviceShadow: DeviceShadowService;
  public logger: Logger;
  public edgeRules: EdgeRuleEngine;
  public otaUpgrade: OTAUpgradeService;
  public coreEngine: CoreEngine;

  constructor(private config: PlatformConfig) {
    this.logger = new Logger(config.logging);
    this.monitoring = new MonitoringService(config.monitoring);
    this.notification = new NotificationService(config.notification);
    this.apiGateway = new ApiGateway(config.apiGateway);
    this.offlineCache = new OfflineCache(config.offlineCache);
    this.configManager = new ConfigManager(config.configManager);
    this.storage = new StorageManager(config.storage);
    this.deviceShadow = new DeviceShadowService(config.deviceShadow);
    this.edgeRules = new EdgeRuleEngine(config.edgeRules);
    this.otaUpgrade = new OTAUpgradeService(config.otaUpgrade);
    this.coreEngine = new CoreEngine(config.coreEngine, this.monitoring, this.notification, this.logger);

    this.setupEventListeners();
  }

  private setupEventListeners(): void {
    this.coreEngine.on('entity-updated', (entity) => {
      this.logger.info('Entity updated', { entityId: entity.id, status: entity.status });
      this.monitoring.increment('entity_updates', 1, { status: entity.status });

      if (entity.status === 'completed') {
        this.notification.send(
          'email',
          'admin@example.com',
          'task-completed',
          { entityId: entity.id, type: entity.type }
        ).catch(() => {});
      }
    });

    this.coreEngine.on('event', (event) => {
      this.logger.debug('Event received', { eventType: event.type });
      this.edgeRules.evaluate(event.data).then(results => {
        for (const result of results) {
          if (result.triggered) {
            this.logger.info('Rule triggered', { ruleId: result.ruleId, ruleName: result.ruleName });
          }
        }
      }).catch(() => {});
    });

    this.deviceShadow.on('delta-available', (shadow) => {
      this.logger.info('Device delta available', { deviceId: shadow.deviceId });
      this.otaUpgrade.emit('device-state-changed', shadow);
    });

    this.offlineCache.on('sync-complete', (results) => {
      this.logger.info('Offline sync complete', { synced: results.filter(r => r.success).length, total: results.length });
      this.monitoring.increment('offline_sync_completed', 1);
    });

    this.monitoring.on('alert-triggered', (alert, metric) => {
      this.logger.warn('Alert triggered', { alertName: alert.name, metric: metric.name, value: metric.value });
      this.notification.send(
        'webhook',
        '/api/alerts',
        'alert-triggered',
        { alert, metric }
      ).catch(() => {});
    });
  }

  async start(): Promise<void> {
    this.logger.info('Starting Cloud Native Platform...');

    await this.configManager.load();
    this.configManager.startWatch();

    this.logger.info('Platform started successfully');
    this.monitoring.increment('platform_started', 1);
  }

  async shutdown(): Promise<void> {
    this.logger.info('Shutting down Cloud Native Platform...');

    this.apiGateway.getCircuitBreaker().reset();
    this.configManager.stopWatch();
    this.configManager.destroy();
    this.monitoring.destroy();
    this.notification.destroy();
    this.offlineCache.destroy();
    this.storage.destroy();
    this.deviceShadow.destroy();
    this.edgeRules.destroy();
    this.otaUpgrade.destroy();
    this.coreEngine.destroy();
    this.logger.destroy();

    this.logger.info('Platform shutdown complete');
  }

  getHealthCheck(): {
    status: 'healthy' | 'degraded';
    components: Record<string, 'healthy' | 'unhealthy'>;
    timestamp: string;
  } {
    return {
      status: 'healthy',
      components: {
        apiGateway: this.apiGateway.getCircuitBreaker().getState() !== 'open' ? 'healthy' : 'unhealthy',
        offlineCache: 'healthy',
        configManager: 'healthy',
        monitoring: 'healthy',
        notification: 'healthy',
        storage: 'healthy',
        deviceShadow: 'healthy',
        edgeRules: 'healthy',
        otaUpgrade: 'healthy',
        coreEngine: 'healthy',
      },
      timestamp: new Date().toISOString(),
    };
  }
}

export * from './types';
export { ApiGateway } from './api-gateway';
export { OfflineCache } from './offline-cache';
export { ConfigManager } from './config-manager';
export { MonitoringService } from './monitoring';
export { NotificationService } from './notification';
export { StorageManager } from './storage-manager';
export { DeviceShadowService } from './device-shadow';
export { Logger, getLogger } from './logging';
export { EdgeRuleEngine } from './edge-rules';
export { OTAUpgradeService } from './ota-upgrade';
export { CoreEngine } from './core';
