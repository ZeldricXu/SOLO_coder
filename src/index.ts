import { v4 as uuidv4 } from 'uuid';
import { CloudNativePlatform, PlatformConfig } from './platform';
import { EdgeRule } from './edge-rules';
import { FirmwareVersion } from './ota-upgrade';

const defaultConfig: PlatformConfig = {
  apiGateway: {
    jwtSecret: 'your-secret-key-here-change-in-production',
    jwtExpiresIn: 3600,
    rateLimit: {
      windowMs: 60000,
      maxRequests: 100,
      keyPrefix: 'rate_limit',
    },
    enableCircuitBreaker: true,
    circuitBreakerThreshold: 5,
    circuitBreakerTimeout: 30000,
  },
  offlineCache: {
    maxSize: 10000,
    defaultTTL: 3600000,
    persistPath: './data/cache.json',
    syncInterval: 30000,
    maxSyncRetries: 5,
    syncBatchSize: 50,
  },
  configManager: {
    sources: [
      { type: 'env', priority: 100, options: { prefix: 'APP_' } },
      { type: 'file', priority: 50, options: { path: './config.yaml' } },
    ],
    defaultNamespace: 'default',
    watchInterval: 30000,
    enableHotReload: true,
  },
  monitoring: {
    collectionInterval: 10000,
    retentionPeriod: 86400000,
    aggregationWindows: [60000, 300000, 3600000],
    enableAlerts: true,
    maxMetrics: 100000,
  },
  notification: {
    channels: [
      { type: 'email', enabled: true, options: { host: 'smtp.example.com', port: 587, user: 'user', pass: 'pass' } },
      { type: 'sms', enabled: false, options: { apiKey: '', apiUrl: '', sender: 'Platform' } },
      { type: 'push', enabled: false, options: {} },
      { type: 'webhook', enabled: true, options: { url: 'https://hooks.example.com/alert' } },
    ],
    defaultMaxRetries: 3,
    retryDelay: 1000,
    batchSize: 10,
    templates: [
      {
        id: 'task-completed',
        name: 'Task Completed',
        type: 'email',
        subject: 'Task {{entityId}} Completed',
        content: 'Your task with ID {{entityId}} of type {{type}} has been completed successfully.',
        variables: ['entityId', 'type'],
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      },
      {
        id: 'alert-triggered',
        name: 'Alert Triggered',
        type: 'webhook',
        content: 'Alert {{alertName}} triggered: metric {{metric}} = {{value}}',
        variables: ['alertName', 'metric', 'value'],
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      },
    ],
  },
  storage: {
    providers: [
      { id: 'local', type: 'local', enabled: true, options: { basePath: './data/backups' } },
    ],
    defaultProvider: 'local',
    backupSchedule: '0 2 * * *',
    retentionPolicy: {
      maxBackups: 30,
      maxAgeDays: 30,
    },
    encryption: {
      enabled: false,
      algorithm: 'aes-256-gcm',
    },
  },
  deviceShadow: {
    syncInterval: 5000,
    maxHistorySize: 100,
    enableDeltaCalculation: true,
    conflictResolution: 'last-write-wins',
  },
  logging: {
    level: 'info',
    enableConsole: true,
    enableJsonFormat: false,
    serviceName: 'cloud-native-platform',
  },
  edgeRules: {
    maxRules: 100,
    evaluationTimeout: 5000,
    enableSandbox: true,
    builtInActions: ['log', 'set_state', 'send_notification', 'http_call', 'device_command'],
  },
  otaUpgrade: {
    maxConcurrentUpgrades: 10,
    downloadTimeout: 300000,
    installTimeout: 600000,
    rolloutPhases: [10, 25, 50, 75, 100],
    defaultRollbackThreshold: 0.1,
    enableDiffUpdates: true,
    maxRollbackAttempts: 3,
  },
  coreEngine: {
    processingTimeout: 30000,
    maxRetries: 3,
    retryDelay: 1000,
    enableCompensation: true,
    eventBusSize: 10000,
  },
};

async function runExample() {
  const platform = new CloudNativePlatform(defaultConfig);
  await platform.start();

  console.log('\n=== Cloud Native Platform Started ===\n');

  const health = platform.getHealthCheck();
  console.log('Health Check:', health.status);

  const authenticator = platform.apiGateway.getAuthenticator();
  await authenticator.registerUser(
    'admin',
    'admin@example.com',
    'admin123',
    ['admin', 'user'],
    ['read', 'write', 'delete']
  );
  console.log('\nUser registered: admin');

  const authResult = await authenticator.authenticate('admin', 'admin123');
  console.log('Authenticated:', authResult ? 'Yes' : 'No');

  platform.apiGateway.registerRoute({
    path: '/api/v1/resources',
    method: 'POST',
    authRequired: true,
    roles: ['admin'],
    permissions: ['write'],
    handler: async (req) => {
      const resource = platform.coreEngine.createResource({
        type: 'job',
        config: req.body as Record<string, unknown> || {},
        labels: {},
      });
      return {
        statusCode: 201,
        headers: { 'Content-Type': 'application/json' },
        body: { code: 201, data: { id: resource.id, status: resource.status } },
      };
    },
  });

  platform.apiGateway.registerRoute({
    path: '/api/v1/resources/:id/status',
    method: 'GET',
    authRequired: true,
    handler: async (req) => {
      const resource = platform.coreEngine.getResource(req.params.id);
      if (!resource) {
        return {
          statusCode: 404,
          headers: { 'Content-Type': 'application/json' },
          body: { code: 404, error: 'Resource not found' },
        };
      }
      return {
        statusCode: 200,
        headers: { 'Content-Type': 'application/json' },
        body: { code: 200, data: { id: resource.id, status: resource.status, progress: 0.8 } },
      };
    },
  });

  const gatewayResponse = await platform.apiGateway.handleRequest({
    method: 'POST',
    path: '/api/v1/resources',
    headers: {
      'Authorization': `Bearer ${authResult?.accessToken}`,
      'Content-Type': 'application/json',
    },
    body: { type: 'job', config: { timeout: 30 } },
  });
  console.log('\nAPI Gateway Response:', gatewayResponse.statusCode, gatewayResponse.body);

  platform.offlineCache.set('user-preferences', { theme: 'dark', language: 'zh-CN' }, 3600000, true);
  const cached = platform.offlineCache.get('user-preferences');
  console.log('\nOffline Cache - Retrieved:', cached);

  platform.configManager.set('app.title', 'Cloud Native Platform');
  platform.configManager.set('api.timeout', 30, 'production');
  const configValue = platform.configManager.get('app.title');
  console.log('Config Manager - app.title:', configValue);

  platform.monitoring.increment('api_requests', 1, { endpoint: '/api/v1/resources', method: 'POST' });
  platform.monitoring.histogram('request_latency', 125, { endpoint: '/api/v1/resources' });

  platform.monitoring.createAlert({
    name: 'High Error Rate',
    metric: 'error_rate',
    condition: 'gt',
    threshold: 0.05,
    duration: 300000,
  });

  const snapshot = platform.monitoring.createSnapshot({ environment: 'production' });
  console.log('Monitoring Snapshot created:', snapshot.snapshot_id);

  const notificationResult = await platform.notification.send(
    'email',
    'user@example.com',
    'task-completed',
    { entityId: 'task_001', type: 'data_processing' }
  );
  console.log('\nNotification sent:', notificationResult.id, notificationResult.status);

  const deviceShadow = platform.deviceShadow.createShadow('device_001', {
    temperature: 25,
    humidity: 60,
    status: 'online',
  });
  console.log('\nDevice Shadow created:', deviceShadow.deviceId);

  platform.deviceShadow.updateDesired('device_001', { temperature: 22, mode: 'eco' });
  const updatedShadow = platform.deviceShadow.getShadow('device_001');
  console.log('Device Shadow delta:', updatedShadow?.delta);

  platform.deviceShadow.updateReported('device_001', { temperature: 22, mode: 'eco' });
  console.log('Device in sync:', platform.deviceShadow.isInSync('device_001'));

  platform.logger.info('Platform initialized successfully', { module: 'main' });
  platform.logger.debug('Debug information', { details: 'verbose' });
  platform.logger.warn('Warning about something', { warning: 'low memory' });

  platform.logger.setLevel('debug');
  console.log('\nLogger level changed to:', platform.logger.getLevel());

  const tempRule: Omit<EdgeRule, 'id' | 'created_at' | 'updated_at'> = {
    name: 'High Temperature Alert',
    description: 'Trigger alert when temperature exceeds 30 degrees',
    conditions: [
      { field: 'temperature', operator: 'gt', value: 30 },
    ],
    conditionOperator: 'AND',
    action: {
      type: 'log',
      params: { level: 'warn', message: 'High temperature detected!' },
    },
    enabled: true,
    priority: 1,
  };

  const rule = platform.edgeRules.addRule(tempRule);
  console.log('\nEdge Rule created:', rule.id, rule.name);

  const ruleResults = await platform.edgeRules.evaluate({ temperature: 35, deviceId: 'sensor_001' });
  console.log('Edge Rule evaluation - triggered:', ruleResults.some(r => r.triggered));

  const firmware: Omit<FirmwareVersion, 'releasedAt'> = {
    version: 'v2.1.0',
    checksum: 'abc123def456',
    size: 1024000,
    releaseNotes: 'Bug fixes and performance improvements',
    url: '/firmware/v2.1.0.bin',
    compatibleDevices: ['device_001', 'device_002'],
  };

  platform.otaUpgrade.addFirmwareVersion(firmware);
  console.log('\nFirmware version added:', firmware.version);

  const diffPatch = platform.otaUpgrade.generateDiffPatch('v2.0.0', 'v2.1.0');
  console.log('Diff patch generated:', diffPatch?.size, 'bytes');

  const batch = platform.otaUpgrade.createBatch('v2.1.0', ['device_001', 'device_002'], 0.2);
  console.log('OTA Batch created:', batch?.id);

  if (batch) {
    platform.otaUpgrade.startBatch(batch.id);
    console.log('OTA Batch started');
  }

  const coreEntity = {
    id: uuidv4(),
    type: 'data_processing',
    status: 'pending' as const,
    attributes: { priority: 'high', input: 'sensor_data' },
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };

  platform.coreEngine.registerEntity(coreEntity);
  console.log('\nCore Entity registered:', coreEntity.id);

  const handlerResult = await platform.coreEngine.executeHandler(
    {
      entityId: coreEntity.id,
      payload: { data: 'test', value: 42 },
      namespace: 'production',
    },
    async (payload, config) => {
      console.log('Processing payload:', payload);
      await new Promise(resolve => setTimeout(resolve, 100));
      return { processed: true, inputValue: payload.value, configApplied: config.timeout };
    }
  );

  console.log('Handler execution result:', handlerResult.success ? 'Success' : 'Failed');
  if (handlerResult.data) {
    console.log('Handler data:', handlerResult.data);
  }

  const finalHealth = platform.getHealthCheck();
  console.log('\n=== Final Health Check ===');
  console.log('Status:', finalHealth.status);
  console.log('Components:', finalHealth.components);

  console.log('\n=== Platform Shutdown ===');
  await platform.shutdown();
  console.log('Platform shutdown complete');
}

if (require.main === module) {
  runExample().catch((error) => {
    console.error('Platform error:', error);
    process.exit(1);
  });
}

export { CloudNativePlatform, PlatformConfig, defaultConfig };
