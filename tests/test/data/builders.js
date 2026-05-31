const { faker } = require('@faker-js/faker');
const { v4: uuidv4 } = require('uuid');

class BaseBuilder {
  constructor() {
    this.data = {};
  }

  with(key, value) {
    this.data[key] = value;
    return this;
  }

  build() {
    return { ...this.data };
  }

  buildMany(count) {
    return Array.from({ length: count }, () => this.build());
  }
}

class FeatureBuilder extends BaseBuilder {
  constructor() {
    super();
    this.data = {
      name: faker.string.alpha({ length: { min: 5, max: 50 } }),
      namespace: 'default',
      description: faker.lorem.sentence(),
      value_type: 'float',
    };
  }

  withName(name) {
    return this.with('name', name);
  }

  withNamespace(namespace) {
    return this.with('namespace', namespace);
  }

  withDescription(description) {
    return this.with('description', description);
  }

  withValueType(valueType) {
    return this.with('value_type', valueType);
  }

  withLabels(labels) {
    return this.with('labels', labels);
  }

  asFloat() {
    return this.withValueType('float');
  }

  asInteger() {
    return this.withValueType('int');
  }

  asString() {
    return this.withValueType('string');
  }

  asBoolean() {
    return this.withValueType('bool');
  }

  asJson() {
    return this.withValueType('json');
  }

  withMaxNameLength() {
    return this.withName(faker.string.alpha(100));
  }

  withEmptyName() {
    return this.withName('');
  }

  withSpecialCharsInName() {
    return this.withName('feature@#$%^&*()');
  }

  withUnicodeName() {
    return this.withName('特征_функция_特徴');
  }
}

class FeatureOnlineRequestBuilder extends BaseBuilder {
  constructor() {
    super();
    this.data = {
      entity_id: uuidv4(),
      feature_names: [],
    };
  }

  withEntityId(entityId) {
    return this.with('entity_id', entityId);
  }

  withFeatureNames(featureNames) {
    return this.with('feature_names', featureNames);
  }

  withFeatures(count) {
    const names = Array.from({ length: count }, (_, i) => `feature_${i}_${faker.string.uuid()}`);
    return this.withFeatureNames(names);
  }

  withEmptyFeatures() {
    return this.withFeatureNames([]);
  }

  withMaxFeatures() {
    return this.withFeatures(100);
  }

  withDuplicateFeatures() {
    const names = ['feature_1', 'feature_1', 'feature_2'];
    return this.withFeatureNames(names);
  }
}

class FeatureOfflineRequestBuilder extends BaseBuilder {
  constructor() {
    super();
    const now = new Date();
    const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
    this.data = {
      entity_ids: [],
      feature_names: [],
      start_time: weekAgo.toISOString(),
      end_time: now.toISOString(),
    };
  }

  withEntityIds(entityIds) {
    return this.with('entity_ids', entityIds);
  }

  withEntities(count) {
    const ids = Array.from({ length: count }, () => uuidv4());
    return this.withEntityIds(ids);
  }

  withFeatureNames(featureNames) {
    return this.with('feature_names', featureNames);
  }

  withFeatures(count) {
    const names = Array.from({ length: count }, (_, i) => `feature_${i}_${faker.string.uuid()}`);
    return this.withFeatureNames(names);
  }

  withStartTime(date) {
    return this.with('start_time', date.toISOString());
  }

  withEndTime(date) {
    return this.with('end_time', date.toISOString());
  }

  withValidTimeRange() {
    const end = new Date();
    const start = new Date(end.getTime() - 24 * 60 * 60 * 1000);
    return this.withStartTime(start).withEndTime(end);
  }

  withInvalidTimeRange() {
    const start = new Date();
    const end = new Date(start.getTime() - 24 * 60 * 60 * 1000);
    return this.withStartTime(start).withEndTime(end);
  }

  withFutureTimeRange() {
    const start = new Date(Date.now() + 24 * 60 * 60 * 1000);
    const end = new Date(start.getTime() + 24 * 60 * 60 * 1000);
    return this.withStartTime(start).withEndTime(end);
  }

  withDistantPastTimeRange() {
    const start = new Date('2000-01-01');
    const end = new Date('2000-01-02');
    return this.withStartTime(start).withEndTime(end);
  }
}

class MetricSnapshotBuilder extends BaseBuilder {
  constructor() {
    super();
    this.data = {
      snapshot_id: `snap_${faker.string.alphanumeric(8)}`,
      timestamp: new Date().toISOString(),
      metrics: {},
      dimensions: {
        host: faker.internet.domainName(),
        region: faker.location.countryCode(),
      },
    };
  }

  withSnapshotId(id) {
    return this.with('snapshot_id', id);
  }

  withTimestamp(date) {
    return this.with('timestamp', date.toISOString());
  }

  withMetrics(metrics) {
    return this.with('metrics', metrics);
  }

  withDefaultMetrics() {
    return this.withMetrics({
      throughput: faker.number.int({ min: 100, max: 10000 }),
      latency_p50: faker.number.float({ min: 10, max: 100 }),
      latency_p99: faker.number.float({ min: 100, max: 500 }),
      error_rate: faker.number.float({ min: 0, max: 0.1 }),
      cpu_usage: faker.number.float({ min: 10, max: 90 }),
      memory_usage: faker.number.float({ min: 20, max: 80 }),
    });
  }

  withDimensions(dimensions) {
    return this.with('dimensions', dimensions);
  }

  withHighLoadMetrics() {
    return this.withMetrics({
      throughput: faker.number.int({ min: 10000, max: 100000 }),
      latency_p99: faker.number.float({ min: 1000, max: 5000 }),
      error_rate: faker.number.float({ min: 0.1, max: 0.5 }),
      cpu_usage: faker.number.float({ min: 90, max: 100 }),
      memory_usage: faker.number.float({ min: 80, max: 95 }),
    });
  }

  withZeroMetrics() {
    return this.withMetrics({
      throughput: 0,
      latency_p99: 0,
      error_rate: 0,
      cpu_usage: 0,
      memory_usage: 0,
    });
  }

  withNegativeMetrics() {
    return this.withMetrics({
      throughput: -100,
      latency_p99: -50,
      error_rate: -0.1,
    });
  }

  withEmptyMetrics() {
    return this.withMetrics({});
  }

  withManyMetrics(count = 100) {
    const metrics = {};
    for (let i = 0; i < count; i++) {
      metrics[`metric_${i}`] = faker.number.float();
    }
    return this.withMetrics(metrics);
  }

  withEmptySnapshotId() {
    return this.withSnapshotId('');
  }

  withInvalidTimestamp() {
    return this.with('timestamp', 'invalid-date');
  }

  withFutureTimestamp() {
    return this.withTimestamp(new Date(Date.now() + 24 * 60 * 60 * 1000));
  }

  withAncientTimestamp() {
    return this.withTimestamp(new Date('1970-01-01'));
  }
}

class LogEntryBuilder extends BaseBuilder {
  constructor() {
    super();
    this.data = {
      level: 'info',
      message: faker.lorem.sentence(),
      timestamp: new Date().toISOString(),
      service: 'structured-logging-service',
      trace_id: uuidv4(),
    };
  }

  withLevel(level) {
    return this.with('level', level);
  }

  withMessage(message) {
    return this.with('message', message);
  }

  withTraceId(traceId) {
    return this.with('trace_id', traceId);
  }

  withUserId(userId) {
    return this.with('user_id', userId);
  }

  withRequestId(requestId) {
    return this.with('request_id', requestId);
  }

  withService(service) {
    return this.with('service', service);
  }

  withError(error) {
    return this.with('error', error instanceof Error ? error.message : error);
  }

  withMetadata(metadata) {
    return this.with('metadata', metadata);
  }

  asDebug() {
    return this.withLevel('debug');
  }

  asInfo() {
    return this.withLevel('info');
  }

  asWarning() {
    return this.withLevel('warning');
  }

  asError() {
    return this.withLevel('error');
  }

  asCritical() {
    return this.withLevel('critical');
  }

  withTransactionContext(transactionId) {
    return this
      .with('transaction_id', transactionId)
      .with('transaction_phase', 'processing');
  }

  withRollbackContext(transactionId, reason) {
    return this
      .with('transaction_id', transactionId)
      .with('transaction_phase', 'rollback')
      .with('rollback_reason', reason);
  }

  withLongMessage(length = 10000) {
    return this.withMessage(faker.string.alpha(length));
  }

  withSpecialCharacters() {
    return this.withMessage('Message with special chars: \x00\x01\x02\n\r\t');
  }

  withLargeContext() {
    const largeData = {};
    for (let i = 0; i < 100; i++) {
      largeData[`key_${i}`] = faker.lorem.paragraph();
    }
    return this.withMetadata(largeData);
  }
}

class AuditLogBuilder extends BaseBuilder {
  constructor() {
    super();
    this.data = {
      action: 'create',
      resource_type: 'feature',
      resource_id: uuidv4(),
      user_id: uuidv4(),
      ip_address: faker.internet.ip(),
      user_agent: faker.internet.userAgent(),
      details: {},
      created_at: new Date().toISOString(),
    };
  }

  withAction(action) {
    return this.with('action', action);
  }

  withResourceType(type) {
    return this.with('resource_type', type);
  }

  withResourceId(id) {
    return this.with('resource_id', id);
  }

  withUserId(id) {
    return this.with('user_id', id);
  }

  withIpAddress(ip) {
    return this.with('ip_address', ip);
  }

  withUserAgent(ua) {
    return this.with('user_agent', ua);
  }

  withDetails(details) {
    return this.with('details', details);
  }

  asCreateAction() {
    return this.withAction('create');
  }

  asUpdateAction() {
    return this.withAction('update');
  }

  asDeleteAction() {
    return this.withAction('delete');
  }

  asLoginAction() {
    return this.withAction('login').withResourceType('user');
  }

  asFailedLogin() {
    return this
      .withAction('login_failed')
      .withResourceType('user')
      .withDetails({ reason: 'invalid_credentials' });
  }

  withInvalidIp() {
    return this.withIpAddress('999.999.999.999');
  }

  withIpv6Address() {
    return this.withIpAddress(faker.internet.ipv6());
  }

  withInternalIp() {
    return this.withIpAddress('127.0.0.1');
  }
}

class TaskExecuteBuilder extends BaseBuilder {
  constructor() {
    super();
    this.data = {
      task_type: 'feature_processing',
      namespace: 'default',
      payload: {},
      priority: 2,
    };
  }

  withTaskType(type) {
    return this.with('task_type', type);
  }

  withNamespace(namespace) {
    return this.with('namespace', namespace);
  }

  withPayload(payload) {
    return this.with('payload', payload);
  }

  withPriority(priority) {
    return this.with('priority', priority);
  }

  withCallbackUrl(url) {
    return this.with('callback_url', url);
  }

  withHighPriority() {
    return this.withPriority(0);
  }

  withLowPriority() {
    return this.withPriority(4);
  }

  withInvalidPriority() {
    return this.withPriority(999);
  }

  withLargePayload() {
    const payload = {};
    for (let i = 0; i < 1000; i++) {
      payload[`field_${i}`] = faker.string.alpha(50);
    }
    return this.withPayload(payload);
  }

  withNestedPayload(depth = 10) {
    let nested = { value: 'deep' };
    for (let i = 0; i < depth; i++) {
      nested = { nested };
    }
    return this.withPayload(nested);
  }

  withEmptyPayload() {
    return this.withPayload({});
  }

  withInvalidPayload() {
    return this.withPayload('not_an_object');
  }
}

class BatchOperationBuilder extends BaseBuilder {
  constructor() {
    super();
    this.data = {
      operations: [],
      timeout_seconds: 60,
    };
  }

  withOperations(operations) {
    return this.with('operations', operations);
  }

  withTimeout(seconds) {
    return this.with('timeout_seconds', seconds);
  }

  addOperation(operation) {
    this.data.operations.push(operation);
    return this;
  }

  withBatchSize(size) {
    const operations = Array.from({ length: size }, (_, i) => ({
      action: ['start', 'stop', 'restart', 'delete'][i % 4],
      id: `rsc_${faker.string.alphanumeric(8)}`,
      params: {},
    }));
    return this.withOperations(operations);
  }

  withEmptyBatch() {
    return this.withOperations([]);
  }

  withLargeBatch(size = 1000) {
    return this.withBatchSize(size);
  }

  withMixedOperations() {
    const operations = [
      { action: 'start', id: 'rsc_001' },
      { action: 'stop', id: 'rsc_002' },
      { action: 'invalid_action', id: 'rsc_003' },
      { action: 'restart', id: 'rsc_004' },
    ];
    return this.withOperations(operations);
  }

  withZeroTimeout() {
    return this.withTimeout(0);
  }

  withNegativeTimeout() {
    return this.withTimeout(-1);
  }

  withExcessiveTimeout() {
    return this.withTimeout(999999);
  }
}

class TestDataFactory {
  static feature() {
    return new FeatureBuilder();
  }

  static featureOnlineRequest() {
    return new FeatureOnlineRequestBuilder();
  }

  static featureOfflineRequest() {
    return new FeatureOfflineRequestBuilder();
  }

  static metricSnapshot() {
    return new MetricSnapshotBuilder();
  }

  static logEntry() {
    return new LogEntryBuilder();
  }

  static auditLog() {
    return new AuditLogBuilder();
  }

  static taskExecute() {
    return new TaskExecuteBuilder();
  }

  static batchOperation() {
    return new BatchOperationBuilder();
  }

  static generateFeatures(count, overrides = {}) {
    return Array.from({ length: count }, () =>
      this.feature().build()
    ).map(f => ({ ...f, ...overrides }));
  }

  static generateMetricSnapshots(count, overrides = {}) {
    return Array.from({ length: count }, (_, i) => {
      const builder = this.metricSnapshot().withDefaultMetrics();
      const timestamp = new Date(Date.now() - i * 60000);
      return { ...builder.withTimestamp(timestamp).build(), ...overrides };
    });
  }

  static generateLogEntries(count, overrides = {}) {
    const levels = ['debug', 'info', 'warning', 'error', 'critical'];
    return Array.from({ length: count }, () => {
      const level = levels[Math.floor(Math.random() * levels.length)];
      return { ...this.logEntry().withLevel(level).build(), ...overrides };
    });
  }
}

module.exports = {
  BaseBuilder,
  FeatureBuilder,
  FeatureOnlineRequestBuilder,
  FeatureOfflineRequestBuilder,
  MetricSnapshotBuilder,
  LogEntryBuilder,
  AuditLogBuilder,
  TaskExecuteBuilder,
  BatchOperationBuilder,
  TestDataFactory,
};
