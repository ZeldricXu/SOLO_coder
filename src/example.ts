import {
  vulnerability,
  gateway,
  contract,
  featureFlags,
  scaffolder,
  storage,
  qualityGate,
  monitoring,
  catalog,
  logger,
} from './index';

async function runExample() {
  logger.info('=== Infrastructure Platform Example ===\n');

  logger.info('--- 1. Feature Flags Example ---');
  const userSegment = featureFlags.segmentManager.createSegment('Beta Users', [
    { field: 'email', operator: 'regex', value: '@company.com$' },
  ]);
  logger.info(`Created segment: ${userSegment.name} (${userSegment.id})`);

  const newFeature = featureFlags.featureFlagManager.createFlag({
    key: 'new_ui_enabled',
    name: 'New UI Feature',
    description: 'Enable the new user interface',
    enabled: true,
    type: 'boolean',
    value: true,
    defaultValue: false,
    targetSegments: [userSegment.id],
    rollout: {
      type: 'gradual',
      percentage: 50,
      startPercentage: 10,
      targetPercentage: 100,
      durationMs: 7 * 24 * 60 * 60 * 1000,
    },
    environment: 'production',
    tags: ['ui', 'feature'],
  });
  logger.info(`Created feature flag: ${newFeature.key} (${newFeature.id})`);

  const evalResult = featureFlags.featureFlagManager.evaluate('new_ui_enabled', {
    userId: 'user_123',
    environment: 'production',
    attributes: { email: 'user@company.com', role: 'admin' },
  });
  logger.info(`Flag evaluation: ${evalResult.key} = ${evalResult.enabled} (${evalResult.reason})`);

  logger.info('\n--- 2. Vulnerability Analysis Example ---');
  const sampleSBOM = JSON.stringify({
    bomFormat: 'CycloneDX',
    specVersion: '1.5',
    version: 1,
    metadata: {
      timestamp: new Date().toISOString(),
      tools: ['example-scanner'],
    },
    components: [
      { name: 'lodash', version: '4.17.20', type: 'library' },
      { name: 'express', version: '4.17.1', type: 'library' },
      { name: 'minimist', version: '1.2.5', type: 'library' },
      { name: 'react', version: '18.2.0', type: 'library' },
    ],
  });

  const vulnReport = await vulnerability.vulnerabilityAnalyzer.analyze({
    sbomContent: sampleSBOM,
    format: 'cyclonedx',
  });
  logger.info(`Vulnerability Report: ${vulnReport.vulnerableComponents} vulnerable components, ${vulnReport.totalVulnerabilities} total vulnerabilities`);
  logger.info(`Critical: ${vulnReport.criticalCount}, High: ${vulnReport.highCount}, Medium: ${vulnReport.mediumCount}, Low: ${vulnReport.lowCount}`);

  logger.info('\n--- 3. Storage Example ---');
  const memoryStore = storage.storageManager.createMemoryStorage('temp-files');
  const uploaded = await storage.storageManager.upload(memoryStore.config.storageId, 'Hello, World!', 'test.txt', {
    contentType: 'text/plain',
    tags: ['example', 'test'],
  });
  logger.info(`Uploaded file: ${uploaded.name} (${uploaded.sizeBytes} bytes)`);

  const downloaded = await storage.storageManager.download(memoryStore.config.storageId, uploaded.fileId);
  logger.info(`Downloaded content: ${downloaded?.toString()}`);

  logger.info('\n--- 4. Code Quality Example ---');
  const qualityReport = await qualityGate.codeAnalyzer.analyze({
    language: 'typescript',
    sourcePaths: ['src'],
    excludedPaths: ['node_modules', 'dist'],
  });
  logger.info(`Quality Report: ${qualityReport.issues.length} issues, ${qualityReport.totalLinesOfCode} LOC`);
  logger.info(`Critical: ${qualityReport.metrics.criticalIssues}, Major: ${qualityReport.metrics.majorIssues}, Minor: ${qualityReport.metrics.minorIssues}`);
  if (qualityReport.qualityGateResult) {
    logger.info(`Quality Gate: ${qualityReport.qualityGateResult.passed ? 'PASSED' : 'FAILED'}`);
  }

  logger.info('\n--- 5. Monitoring Example ---');
  monitoring.monitoringManager.getMetricsCollector().incrementCounter('requests_total', { endpoint: '/api/test' });
  monitoring.monitoringManager.getMetricsCollector().recordHistogram('request_latency', 145, { endpoint: '/api/test' });
  monitoring.monitoringManager.recordRequest(145, true);

  const health = monitoring.monitoringManager.getHealth();
  logger.info(`System Health: ${health.status}`);
  logger.info(`Throughput: ${health.metrics.throughput.toFixed(2)} req/s, Avg Latency: ${health.metrics.avgLatency.toFixed(2)}ms`);

  logger.info('\n--- 6. Software Catalog Example ---');
  const apiService = catalog.serviceCatalog.registerService({
    name: 'user-api',
    type: 'service',
    description: 'User management API service',
    version: '2.1.0',
    owner: 'platform-team',
    team: 'backend',
    tags: ['api', 'user-management', 'rest'],
    status: 'active',
    lifecycleStage: 'production',
    endpoints: [
      { name: 'rest-api', protocol: 'http', url: 'https://api.example.com/users', environment: 'production' },
    ],
    repositories: [
      { type: 'git', url: 'https://github.com/example/user-api.git' },
    ],
    metadata: {},
  });
  logger.info(`Registered service: ${apiService.name} (${apiService.serviceId})`);

  const dbService = catalog.serviceCatalog.registerService({
    name: 'user-db',
    type: 'database',
    description: 'User database',
    version: '14.0',
    team: 'data',
    tags: ['database', 'postgresql'],
    status: 'active',
    lifecycleStage: 'production',
    endpoints: [],
    repositories: [],
    metadata: {},
  });

  const dep = catalog.dependencyManager.addDependency(apiService.serviceId, dbService.serviceId, 'depends_on', {
    isCritical: true,
    description: 'Primary data store',
  });
  logger.info(`Created dependency: ${dep.relationship} (critical: ${dep.isCritical})`);

  const graph = catalog.dependencyManager.buildDependencyGraph();
  logger.info(`Dependency graph: ${graph.nodes.length} nodes, ${graph.edges.length} edges`);

  logger.info('\n--- 7. Scaffolder Example ---');
  const templates = scaffolder.scaffolder.listTemplates();
  logger.info(`Available templates: ${templates.map(t => t.name).join(', ')}`);

  logger.info('\n=== Refactored Modules Examples ===\n');

  logger.info('--- 8. Refactored Vulnerability Module Example ---');
  const customPipeline = new vulnerability.AnalysisPipeline(
    new vulnerability.SBOMParser(),
    vulnerability.cveDatabase,
    new vulnerability.FixRecommender(),
    new vulnerability.ReportGenerator()
  );
  logger.info('Custom AnalysisPipeline created with dependency injection');

  const pipelineReport = await customPipeline.execute({
    sbomContent: sampleSBOM,
    format: 'cyclonedx',
    severityFilter: ['CRITICAL', 'HIGH'],
  });
  logger.info(`Pipeline analysis: ${pipelineReport.vulnerableComponents} vulnerable components (filtered)`);

  logger.info('\n--- 9. Refactored API Gateway Example ---');
  const apiGateway = new gateway.APIGateway({ port: 8081 });

  const loggingMiddleware: gateway.IGatewayMiddleware = {
    name: 'request-logger',
    preRequest: async (req, route) => {
      logger.debug(`Request to ${route.path}`);
      return req;
    },
  };
  apiGateway.useMiddleware(loggingMiddleware);
  logger.info('Gateway middleware registered: request-logger');

  const route = apiGateway.addRoute({
    path: '/api/users',
    method: 'GET',
    target: { host: 'localhost', port: 3000, protocol: 'http' },
    protocol: 'http',
    enabled: true,
    timeoutMs: 30000,
    transformations: [],
  });
  logger.info(`Route added: ${route.method} ${route.path} -> ${route.target.host}:${route.target.port}`);
  logger.info(`Registered handlers: ${apiGateway.getHandlers().length}`);

  logger.info('\n--- 10. Refactored Contract Testing Example ---');
  const openAPIValidator = contract.contractProviderFactory.getValidator('openapi');
  const graphQLValidator = contract.contractProviderFactory.getValidator('graphql');
  logger.info(`Supported contract types: ${contract.contractProviderFactory.getSupportedTypes().join(', ')}`);

  const sampleOpenAPI = {
    openapi: '3.0.0',
    info: { title: 'Test API', version: '1.0.0' },
    paths: {},
  };
  const schemaValidation = openAPIValidator?.validateSchema(sampleOpenAPI as any);
  logger.info(`OpenAPI schema validation: ${schemaValidation?.valid ? 'VALID' : 'INVALID'}`);

  const mockGenerator = contract.contractProviderFactory.getMockGenerator('openapi');
  const mockConfig = mockGenerator?.generate(sampleOpenAPI as any, { port: 3001 });
  logger.info(`Mock config generated with ${mockConfig?.endpoints.length || 0} endpoints`);

  logger.info('\n=== All examples completed successfully! ===');
}

runExample().catch(console.error);
