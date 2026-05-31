import { createApp, createAppWithEnhancements } from './index';
import { TraceSpan, SamplingStrategy, NotificationChannel, SuppressionRule, AlertRule, AggregationRule, LifecyclePolicy, SchemaMigration, TracingConfig, ScenarioType } from './types';
import { DefaultPriorityStrategy, TypeBasedPriorityStrategy, RuleBasedSuppressionStrategy, RateLimitSuppressionStrategy } from './notification';

async function runExample() {
  console.log('=== Observability Platform Example (Enhanced) ===\n');

  const tracingConfig: TracingConfig = {
    bufferTimeout: 30000,
    maxBufferSize: 1000,
    defaultSampleRate: 1.0,
    currentScenario: 'production' as ScenarioType,
    scenarios: {
      production: {
        name: 'Production',
        bufferTimeout: 30000,
        maxBufferSize: 1000,
        sampleRate: 0.1,
        enableTailSampling: true,
        strategies: [],
      },
      staging: {
        name: 'Staging',
        bufferTimeout: 60000,
        maxBufferSize: 2000,
        sampleRate: 0.5,
        enableTailSampling: true,
        strategies: [],
      },
      development: {
        name: 'Development',
        bufferTimeout: 120000,
        maxBufferSize: 5000,
        sampleRate: 1.0,
        enableTailSampling: false,
        strategies: [],
      },
      custom: {
        name: 'Custom',
        bufferTimeout: 30000,
        maxBufferSize: 1000,
        sampleRate: 1.0,
        enableTailSampling: true,
        strategies: [],
      },
    },
  };

  const { modules, start, stop } = createAppWithEnhancements('./example-data', tracingConfig);
  const { tracing, notification, profiling, metrics, storage, alerting, anomaly, config, dataAccess, tracingConfigManager, strategyRegistry } = modules;

  console.log('1. Setting up sampling strategies...');
  const headStrategy: SamplingStrategy = {
    id: 'head-001',
    name: 'Default Head Sampling',
    type: 'head',
    rule: { sampleRate: 0.1, serviceName: 'api-gateway' },
    priority: 1,
    enabled: true,
  };
  tracing.strategyManager.addStrategy(headStrategy);

  const tailStrategy: SamplingStrategy = {
    id: 'tail-001',
    name: 'Error Tail Sampling',
    type: 'tail',
    rule: { sampleRate: 1.0, errorOnly: true, minDuration: 5000 },
    priority: 10,
    enabled: true,
  };
  tracing.strategyManager.addStrategy(tailStrategy);
  console.log('   Sampling strategies configured\n');

  console.log('2. Setting up notification channels...');
  const emailChannel: NotificationChannel = {
    id: 'email-001',
    type: 'email',
    config: { to: ['admin@example.com'], from: 'alerts@example.com' },
    enabled: true,
    priorityThreshold: 'medium',
  };
  notification.channelManager.addChannel(emailChannel);

  const slackChannel: NotificationChannel = {
    id: 'slack-001',
    type: 'slack',
    config: { webhookUrl: 'https://hooks.slack.com/xxx' },
    enabled: true,
    priorityThreshold: 'high',
  };
  notification.channelManager.addChannel(slackChannel);
  console.log('   Notification channels configured\n');

  console.log('3. Setting up suppression rules...');
  const suppressionRule: SuppressionRule = {
    id: 'suppress-001',
    name: 'Rate limit alerts',
    matcher: { tags: ['rate-limit'] },
    duration: 300000,
    maxSuppressions: 10,
    enabled: true,
  };
  notification.suppressionManager.addRule(suppressionRule);
  console.log('   Suppression rules configured\n');

  console.log('4. Setting up aggregation rules...');
  const aggRule: AggregationRule = {
    id: 'agg-001',
    metric: 'request_duration',
    function: 'avg',
    groupBy: ['service', 'endpoint'],
    interval: 60000,
  };
  metrics.aggregationEngine.addRule(aggRule);
  console.log('   Aggregation rules configured\n');

  console.log('5. Setting up lifecycle policies...');
  const lifecyclePolicy: LifecyclePolicy = {
    id: 'lifecycle-001',
    name: 'Default retention',
    prefix: 'traces/',
    transitions: [{ days: 30, storageClass: 'archive' }],
    expirationDays: 90,
    enabled: true,
  };
  storage.lifecycleManager.addPolicy(lifecyclePolicy);
  console.log('   Lifecycle policies configured\n');

  console.log('6. Setting up alert rules...');
  const alertRule: AlertRule = {
    id: 'alert-001',
    name: 'High error rate',
    metric: 'error_rate',
    condition: { operator: 'gt', threshold: 0.05 },
    threshold: 0.05,
    duration: 300000,
    severity: 'critical',
    notificationChannels: ['slack-001', 'email-001'],
    enabled: true,
    labels: { service: 'api-gateway' },
  };
  await alerting.pipeline.createRule(alertRule);
  console.log('   Alert rules configured\n');

  console.log('7. Setting up config schemas...');
  config.manager.registerSchema('service/config', {
    timeout: 'number',
    retries: 'number',
    enabled: 'boolean',
  });
  await config.manager.set('service/config', { timeout: 30, retries: 3, enabled: true }, 'Initial config');
  console.log('   Configuration set up\n');

  console.log('8. Setting up database migrations...');
  const migration: SchemaMigration = {
    version: 1,
    name: 'initial_schema',
    up: 'CREATE TABLE IF NOT EXISTS entities (id TEXT PRIMARY KEY, type TEXT, status TEXT, attributes JSONB, created_at TIMESTAMP, updated_at TIMESTAMP)',
    down: 'DROP TABLE entities',
  };
  dataAccess.migrationManager.registerMigration(migration);
  await dataAccess.migrationManager.migrate();
  console.log('   Migrations applied\n');

  console.log('9. [Enhanced] Dynamic tracing config example...');
  if (tracingConfigManager) {
    console.log('   Current scenario:', tracingConfigManager.getCurrentScenario());
    console.log('   Current sampling rate:', tracingConfigManager.getCurrentScenarioConfig()?.sampleRate);

    tracingConfigManager.updateScenario('production', {
      sampleRate: 0.2,
    });
    console.log('   Updated production sampling rate to 0.2');

    tracingConfigManager.setCurrentScenario('staging' as ScenarioType);
    console.log('   Switched to staging scenario');

    const unsubscribe = tracingConfigManager.subscribe((config, version) => {
      console.log(`   Config updated to version ${version}`);
    });
    unsubscribe();
  }
  console.log();

  console.log('10. [Enhanced] Notification strategies example...');
  if (strategyRegistry) {
    const priorityStrategy = new TypeBasedPriorityStrategy();
    strategyRegistry.registerPriorityStrategy(priorityStrategy);
    console.log('   Registered TypeBasedPriorityStrategy');

    const rateLimitStrategy = new RateLimitSuppressionStrategy(100, 60000);
    strategyRegistry.registerSuppressionStrategy(rateLimitStrategy);
    console.log('   Registered RateLimitSuppressionStrategy');

    console.log('   Available priority strategies:', strategyRegistry.listPriorityStrategies().map(s => s.id));
    console.log('   Available suppression strategies:', strategyRegistry.listSuppressionStrategies().map(s => s.id));

    const switched = strategyRegistry.setActivePriorityStrategy('type-based-priority');
    console.log('   Switched to type-based priority strategy:', switched);
  }
  console.log();

  console.log('11. [Enhanced] Async profiling example...');
  const eventBus = profiling.eventBus;
  const eventUnsubscribe = eventBus.on('session_completed', (event) => {
    console.log(`   Event: Session ${event.sessionId} completed`);
    const flameGraph = profiling.manager.generateFlameGraphSVG(event.sessionId, 800, 400);
    console.log(`   Flame graph generated for session ${event.sessionId}: ${flameGraph ? 'yes' : 'no'}`);
  });

  console.log('   Starting async CPU profiling session...');
  const asyncSession = await profiling.manager.startSession('cpu', 200);
  console.log(`   Session started: ${asyncSession.id}, status=${asyncSession.status}`);
  console.log('   Active sessions:', profiling.manager.getActiveSessionCount());

  console.log('   Starting blocking CPU profiling session...');
  const syncSession = await profiling.manager.startSessionAsync('cpu', 100);
  console.log(`   Session completed: ${syncSession.id}, samples=${syncSession.samples.length}`);
  eventUnsubscribe();
  console.log();

  console.log('12. Sending example trace spans...');
  const span: TraceSpan = {
    traceId: 'trace-example-001',
    spanId: 'span-001',
    name: 'GET /api/users',
    serviceName: 'api-gateway',
    startTime: new Date().toISOString(),
    endTime: new Date(Date.now() + 150).toISOString(),
    duration: 150,
    status: 'OK',
    attributes: { 'http.method': 'GET', 'http.status_code': 200 },
  };
  const processedSpan = await tracing.pipeline.processSpan(span);
  console.log(`   Processed span: ${processedSpan.name}, sampled: ${processedSpan.sampled}`);

  const finalizeResult = await tracing.pipeline.finalizeTrace('trace-example-001');
  console.log(`   Trace finalized: sampled=${finalizeResult.sampled}, spans=${finalizeResult.spans.length}\n`);

  console.log('13. Sending example metrics...');
  for (let i = 0; i < 10; i++) {
    await metrics.pipeline.ingest({
      timestamp: Date.now() - (10 - i) * 1000,
      metric: 'request_duration',
      value: 100 + Math.random() * 200,
      tags: { service: 'api-gateway', endpoint: '/api/users' },
    });
  }
  console.log('   Metrics ingested\n');

  console.log('14. Running anomaly detection...');
  const anomalyResults = await anomaly.pipeline.process('request_duration', { service: 'api-gateway' }, 500);
  console.log(`   Anomalies detected: ${anomalyResults.length}`);
  for (const result of anomalyResults) {
    console.log(`   - ${result.algorithm}: value=${result.value}, expected=${result.expected.toFixed(2)}, severity=${result.severity}`);
  }
  console.log();

  console.log('15. Sending example notification...');
  const notificationResult = await notification.router.send({
    type: 'alert',
    priority: 'high',
    title: 'High latency detected',
    message: 'Average request duration exceeded 500ms for the last 5 minutes',
    source: 'metrics',
    tags: ['latency', 'performance'],
  });
  console.log(`   Notification sent: delivered=${notificationResult.delivered}, suppressed=${notificationResult.suppressed}\n`);

  console.log('16. Storing example data...');
  const storedObj = await storage.storageManager.put(
    'traces/trace-example-001.json',
    Buffer.from(JSON.stringify({ traceId: 'trace-example-001', spans: finalizeResult.spans })),
    { 'content-type': 'application/json' }
  );
  console.log(`   Stored object: ${storedObj.key}, size=${storedObj.size} bytes\n`);

  console.log('17. Evaluating alert rules...');
  const evalResults = await alerting.pipeline.evaluate();
  console.log(`   Alert evaluation: ${evalResults.length} results`);
  for (const result of evalResults) {
    console.log(`   - Rule ${result.ruleId}: state=${result.state}, value=${result.value}, violated=${result.violated}`);
  }
  console.log();

  console.log('18. Config operations...');
  const currentConfig = config.manager.get('service/config');
  console.log(`   Current config version: v${currentConfig?.version}`);

  await config.manager.setParameter('service/config', 'timeout', 60);
  const updatedConfig = config.manager.get('service/config');
  console.log(`   Updated config version: v${updatedConfig?.version}, timeout=${updatedConfig?.parameters.timeout}`);

  const versions = config.manager.listVersions('service/config');
  console.log(`   Total versions: ${versions.length}`);

  const diff = config.manager.diff('service/config', 1, 2);
  if (diff) {
    console.log(`   Changes from v1 to v2: modified=${diff.modified.length} fields`);
  }
  console.log();

  console.log('=== Example Complete ===');
  console.log('Available algorithms:', anomaly.pipeline.getAvailableAlgorithms());
  console.log('Firing alerts:', alerting.pipeline.getFiringAlerts().length);
  console.log('Total profiling sessions:', profiling.manager.listSessions().length);

  await stop();
}

runExample().catch(console.error);
