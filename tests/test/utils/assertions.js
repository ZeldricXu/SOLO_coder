const assert = require('assert');

class CustomAssertions {
  static assertResponseStructure(response, expectedStatus = 200) {
    expect(response.status).toBe(expectedStatus);
    expect(response.body).toHaveProperty('code');
    expect(response.body).toHaveProperty('data');
    expect(response.body).toHaveProperty('request_id');
  }

  static assertSuccessResponse(response, expectedStatus = 200) {
    this.assertResponseStructure(response, expectedStatus);
    expect(response.body.code).toBe(expectedStatus);
    expect(response.body.request_id).toBeTruthy();
  }

  static assertErrorResponse(response, expectedStatus, expectedMessagePattern) {
    expect(response.status).toBe(expectedStatus);
    expect(response.body).toHaveProperty('code');
    expect(response.body).toHaveProperty('message');
    
    if (expectedMessagePattern) {
      expect(response.body.message).toMatch(expectedMessagePattern);
    }
  }

  static assertValidationError(response, fieldName) {
    this.assertErrorResponse(response, 422);
    if (fieldName && response.body.details) {
      const hasFieldError = Object.keys(response.body.details).some(key =>
        key.includes(fieldName) || response.body.details[key]?.includes(fieldName)
      );
      expect(hasFieldError).toBe(true);
    }
  }

  static assertNotFound(response) {
    this.assertErrorResponse(response, 404, /not found/i);
  }

  static assertConflict(response) {
    this.assertErrorResponse(response, 409);
  }

  static assertUnauthorized(response) {
    this.assertErrorResponse(response, 401);
  }

  static assertForbidden(response) {
    this.assertErrorResponse(response, 403);
  }

  static assertRateLimited(response) {
    this.assertErrorResponse(response, 429);
  }

  static assertFeatureStructure(feature) {
    expect(feature).toHaveProperty('id');
    expect(feature).toHaveProperty('name');
    expect(feature).toHaveProperty('namespace');
    expect(feature).toHaveProperty('value_type');
    expect(feature).toHaveProperty('created_at');
    expect(feature).toHaveProperty('updated_at');
  }

  static assertMetricStructure(metric) {
    expect(metric).toHaveProperty('snapshot_id');
    expect(metric).toHaveProperty('timestamp');
    expect(metric).toHaveProperty('metrics');
    expect(typeof metric.metrics).toBe('object');
  }

  static assertTaskStructure(task) {
    expect(task).toHaveProperty('task_id');
    expect(task).toHaveProperty('status');
    expect(['completed', 'failed', 'running', 'pending']).toContain(task.status);
  }

  static assertLogStructure(log) {
    expect(log).toHaveProperty('level');
    expect(log).toHaveProperty('message');
    expect(log).toHaveProperty('timestamp');
  }

  static assertAuditLogStructure(auditLog) {
    expect(auditLog).toHaveProperty('action');
    expect(auditLog).toHaveProperty('resource_type');
    expect(auditLog).toHaveProperty('created_at');
  }

  static assertPaginatedResponse(response) {
    this.assertSuccessResponse(response);
    expect(response.body.data).toHaveProperty('items');
    expect(Array.isArray(response.body.data.items)).toBe(true);
    expect(response.body.data).toHaveProperty('total');
    expect(response.body.data).toHaveProperty('page');
    expect(response.body.data).toHaveProperty('page_size');
  }

  static assertMetricsData(metrics, expectedMetrics = []) {
    expectedMetrics.forEach(metricName => {
      expect(metrics).toHaveProperty(metricName);
    });
  }

  static assertTimestampsInOrder(timestamps, ascending = true) {
    for (let i = 1; i < timestamps.length; i++) {
      const prev = new Date(timestamps[i - 1]).getTime();
      const curr = new Date(timestamps[i]).getTime();
      if (ascending) {
        expect(curr).toBeGreaterThanOrEqual(prev);
      } else {
        expect(curr).toBeLessThanOrEqual(prev);
      }
    }
  }

  static assertApproximatelyEqual(actual, expected, tolerance = 0.01) {
    const diff = Math.abs(actual - expected);
    expect(diff).toBeLessThanOrEqual(Math.abs(expected) * tolerance);
  }

  static assertJsonStructure(actual, expected) {
    Object.keys(expected).forEach(key => {
      expect(actual).toHaveProperty(key);
      if (typeof expected[key] === 'object' && expected[key] !== null) {
        this.assertJsonStructure(actual[key], expected[key]);
      }
    });
  }

  static assertAllHaveProperty(array, property) {
    expect(Array.isArray(array)).toBe(true);
    array.forEach(item => {
      expect(item).toHaveProperty(property);
    });
  }

  static assertAllUnique(array, property) {
    expect(Array.isArray(array)).toBe(true);
    const values = array.map(item => property ? item[property] : item);
    const unique = new Set(values);
    expect(unique.size).toBe(values.length);
  }

  static assertWithinRange(value, min, max) {
    expect(value).toBeGreaterThanOrEqual(min);
    expect(value).toBeLessThanOrEqual(max);
  }

  static assertValidUuid(value) {
    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    expect(uuidRegex.test(value)).toBe(true);
  }

  static assertValidIsoDate(value) {
    const date = new Date(value);
    expect(!isNaN(date.getTime())).toBe(true);
    expect(date.toISOString()).toBe(value);
  }

  static assertValidIpAddress(value) {
    const ipv4Regex = /^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/;
    const ipv6Regex = /^(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$/;
    expect(ipv4Regex.test(value) || ipv6Regex.test(value)).toBe(true);
  }

  static assertHasLogWithLevel(logs, level) {
    const hasLevel = logs.some(log => log.level === level || log.level?.toLowerCase() === level.toLowerCase());
    expect(hasLevel).toBe(true);
  }

  static assertTransactionRollback(logs, transactionId) {
    const rollbackLog = logs.find(
      log => log.transaction_id === transactionId && log.transaction_phase === 'rollback'
    );
    expect(rollbackLog).toBeDefined();
    expect(rollbackLog.rollback_reason).toBeTruthy();
  }

  static assertMetricsHaveNoNegativeValues(metrics) {
    Object.values(metrics).forEach(value => {
      if (typeof value === 'number') {
        expect(value).toBeGreaterThanOrEqual(0);
      }
    });
  }
}

module.exports = CustomAssertions;
