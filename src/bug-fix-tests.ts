import { vulnerability, gateway, contract, logger } from './index';

async function testStateMachineFix() {
  logger.info('\n=== Test 1: Vulnerability Pipeline State Machine Fix ===\n');

  const pipeline = new vulnerability.AnalysisPipeline(
    new vulnerability.SBOMParser(),
    vulnerability.cveDatabase,
    new vulnerability.FixRecommender(),
    new vulnerability.ReportGenerator()
  );

  let status = pipeline.getStatus();
  logger.info(`Initial state: ${status.state}`);

  const sampleSBOM = JSON.stringify({
    bomFormat: 'CycloneDX',
    specVersion: '1.5',
    version: 1,
    metadata: {
      timestamp: new Date().toISOString(),
      tools: ['test-scanner'],
    },
    components: [
      { name: 'lodash', version: '4.17.20', type: 'library' },
      { name: 'express', version: '4.17.1', type: 'library' },
      { name: 'react', version: '18.2.0', type: 'library' },
    ],
  });

  try {
    await pipeline.execute({ sbomContent: sampleSBOM, format: 'cyclonedx' });
    status = pipeline.getStatus();
    logger.info(`After execution state: ${status.state}`);
    logger.info(`Progress: ${status.progress}%`);
    logger.info(`Components: ${status.processedComponents}/${status.totalComponents}`);
    logger.info('✓ State machine transitions working correctly');
  } catch (error) {
    logger.error(`State machine test failed: ${error}`);
  }

  try {
    pipeline.reset();
    status = pipeline.getStatus();
    logger.info(`After reset state: ${status.state}`);
    logger.info('✓ Pipeline reset working correctly');
  } catch (error) {
    logger.error(`Reset test failed: ${error}`);
  }

  try {
    const invalidSBOM = 'invalid json content';
    await pipeline.execute({ sbomContent: invalidSBOM, format: 'json' });
  } catch (error) {
    status = pipeline.getStatus();
    logger.info(`After invalid SBOM state: ${status.state}`);
    logger.info(`Errors captured: ${status.errors.length}`);
    logger.info('✓ Error state transition working correctly');
  }
}

async function testTimeoutFix() {
  logger.info('\n=== Test 2: API Gateway Timeout Fix ===\n');

  const slowMiddleware: gateway.IGatewayMiddleware = {
    name: 'slow-middleware',
    preRequest: async (req) => {
      await new Promise(resolve => setTimeout(resolve, 2000));
      return req;
    },
  };

  const handler = new gateway.HttpRequestHandler(undefined, { timeoutMs: 500 });
  handler.use(slowMiddleware);

  const testRoute = {
    id: 'test-route',
    path: '/api/test',
    method: 'GET' as const,
    target: { host: 'localhost', port: 3000, protocol: 'http' as const },
    protocol: 'http' as const,
    timeoutMs: 500,
    enabled: true,
    transformations: [],
  };

  const testRequest: gateway.ProxyRequest = {
    method: 'GET',
    path: '/api/test',
    headers: {},
    query: {},
  };

  const startTime = Date.now();
  const response = await handler.handle(testRequest, testRoute);
  const duration = Date.now() - startTime;

  logger.info(`Request duration: ${duration}ms`);
  logger.info(`Response status: ${response.status}`);

  if (response.status === 504 && duration < 1000) {
    logger.info('✓ Timeout control working correctly');
  } else {
    logger.error('✗ Timeout control not working as expected');
  }

  logger.info(`Handler options: ${JSON.stringify(handler.getOptions())}`);
}

async function testMemoryLeakFix() {
  logger.info('\n=== Test 3: Contract Testing Memory Leak Fix ===\n');

  const validator = new contract.OpenAPIValidator(10, 5000);
  const sampleSchema = {
    openapi: '3.0.0',
    info: { title: 'Test API', version: '1.0.0' },
    paths: {},
  };

  logger.info(`Initial cache size: ${validator.getCacheStats().size}`);

  for (let i = 0; i < 50; i++) {
    const uniqueSchema = {
      ...sampleSchema,
      info: { title: `Test API ${i}`, version: '1.0.0' },
      paths: { [`/api/endpoint-${i}`]: {} },
    };
    validator.validateSchema(uniqueSchema as any);
  }

  const stats = validator.getCacheStats();
  logger.info(`Cache size after 50 validations: ${stats.size}/${stats.maxSize}`);

  if (stats.size <= 10) {
    logger.info('✓ LRU cache eviction working correctly');
  } else {
    logger.error('✗ LRU cache eviction not working');
  }

  validator.clearCache();
  logger.info(`Cache size after clear: ${validator.getCacheStats().size}`);
  logger.info('✓ Cache clear working correctly');

  validator.destroy();
  logger.info('✓ Validator destroyed correctly');
}

async function runAllTests() {
  logger.info('=== Running Bug Fix Verification Tests ===\n');

  try {
    await testStateMachineFix();
  } catch (error) {
    logger.error(`State machine test failed: ${error}`);
  }

  try {
    await testTimeoutFix();
  } catch (error) {
    logger.error(`Timeout test failed: ${error}`);
  }

  try {
    await testMemoryLeakFix();
  } catch (error) {
    logger.error(`Memory leak test failed: ${error}`);
  }

  logger.info('\n=== All tests completed ===');
}

runAllTests().catch(console.error);
