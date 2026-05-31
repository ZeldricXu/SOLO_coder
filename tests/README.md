# Structured Logging Platform - Test Suite

## Overview

This test suite provides comprehensive unit and integration tests for the three high-risk modules of the Structured Logging Platform:

1. **Feature Store Service Module** - Boundary condition testing
2. **Logging Module** - Transaction rollback correctness testing
3. **Monitoring Module** - Parameter validation completeness testing

## Tech Stack

- **Test Framework**: Jest 29.x
- **HTTP Client**: Supertest 6.x
- **Test Data**: @faker-js/faker 8.x + Custom Test Data Builders
- **Language**: JavaScript (Node.js 18+)

## Project Structure

```
tests/
├── package.json              # Dependencies and scripts
├── jest.config.js            # Jest configuration
├── .env.test                 # Test environment variables
├── README.md                 # This file
└── test/
    ├── global-setup.js       # Test suite setup
    ├── global-teardown.js    # Test suite teardown
    ├── setup.js              # Per-test setup
    ├── data/
    │   ├── index.js
    │   └── builders.js       # Test Data Builders (Builder Pattern)
    ├── utils/
    │   ├── index.js
    │   ├── api-client.js     # API client wrapper
    │   └── assertions.js     # Custom assertion utilities
    ├── feature-store.test.js # Feature store boundary tests
    ├── logging.test.js       # Logging transaction tests
    └── monitoring.test.js    # Monitoring validation tests
```

## Test Data Builders

The test suite uses the Builder Pattern to separate test data construction from test logic:

### Available Builders

- `FeatureBuilder` - Build feature registration requests
- `FeatureOnlineRequestBuilder` - Build online feature serving requests
- `FeatureOfflineRequestBuilder` - Build offline feature retrieval requests
- `MetricSnapshotBuilder` - Build metric snapshot data
- `LogEntryBuilder` - Build structured log entries
- `AuditLogBuilder` - Build audit log entries
- `TaskExecuteBuilder` - Build task execution requests
- `BatchOperationBuilder` - Build batch operation requests

### Builder Usage Example

```javascript
const { TestDataFactory } = require('./data/builders');

// Build a feature with specific properties
const feature = TestDataFactory.feature()
  .withName('user_click_rate')
  .withNamespace('production')
  .asFloat()
  .withDescription('User click through rate')
  .build();

// Build an offline request with valid time range
const offlineRequest = TestDataFactory.featureOfflineRequest()
  .withEntities(100)
  .withFeatures(5)
  .withValidTimeRange()
  .build();

// Build a log entry for transaction rollback testing
const rollbackLog = TestDataFactory.logEntry()
  .asError()
  .withRollbackContext('txn_123', 'Constraint violation')
  .build();

// Build a metric snapshot with default metrics
const snapshot = TestDataFactory.metricSnapshot()
  .withDefaultMetrics()
  .withDimensions({ host: 'server-01', region: 'us-east-1' })
  .build();
```

### Factory Methods

```javascript
// Generate multiple features
const features = TestDataFactory.generateFeatures(50, { namespace: 'test' });

// Generate metric snapshots over time
const snapshots = TestDataFactory.generateMetricSnapshots(24);

// Generate mixed log entries
const logs = TestDataFactory.generateLogEntries(100);
```

## Test Coverage

### 1. Feature Store Service Module (46 tests)

**Feature Registration Boundary Conditions:**
- Feature name validation (max length, empty, special chars, unicode)
- Duplicate name detection (same namespace vs different namespaces)
- Value type validation (valid types vs invalid types)
- Namespace validation (empty, long names)

**Online Feature Serving:**
- Entity ID validation (long, empty, special chars)
- Feature names list validation (max, empty, duplicates, non-existent)

**Offline Feature Retrieval:**
- Time range validation (valid, invalid, future, ancient, large ranges)
- Entity IDs validation (multiple, empty)

**CRUD Edge Cases:**
- Pagination boundary tests (large page size, negative page, zero page size)
- Non-existent resource handling
- Invalid UUID format handling
- Concurrent creation requests

**Consistency Check:**
- Time window validation (small, large, negative, zero)
- Multiple features check

### 2. Logging Module (44 tests)

**Structured Log Output:**
- JSON format validation
- Required fields presence
- Log level handling (debug, info, warning, error, critical)
- Context propagation (trace_id, request_id, user_id)

**Transaction Context:**
- Transaction start logging
- Rollback logging with reason
- Consistent transaction_id across logs
- Phase transition logging

**Transaction Rollback Scenarios:**
- Validation error rollback
- Timeout rollback
- Resource acquisition failure rollback
- External service failure rollback
- Database constraint violation rollback
- Concurrency conflict rollback

**Rollback Completeness:**
- Rollback start and completion logging
- Rollback duration tracking
- Step-by-step rollback logging
- Rollback error handling

**Error Handling:**
- Large message handling
- Special character handling
- Large metadata context
- Nested exception logging

**Audit Log Integration:**
- CRUD action audit trails
- Failed login logging
- IP address tracking (IPv4, IPv6, internal)
- User agent logging

### 3. Monitoring Module (48 tests)

**Metric Snapshot Creation:**
- snapshot_id validation (valid, empty, long, special chars)
- timestamp validation (valid, invalid format, future, ancient, missing)
- metrics validation (valid, empty, non-object, negative, zero, many values)
- dimensions validation (valid, empty, null, many, special chars, non-string)

**Metrics Query:**
- Filter parameter validation
- Time range validation
- Dimension filtering
- Pagination validation

**Audit Log Query:**
- Action filtering
- Resource type filtering
- User ID validation
- Time range filtering

**Data Integrity:**
- Negative value detection
- Latency percentile ordering
- Error rate range validation (0-1)
- CPU/memory percentage validation

**High Load Scenarios:**
- Rapid snapshot creation
- High load metrics handling
- Large payload handling

**Response Structure:**
- Consistent response format across endpoints

## Running Tests

### Prerequisites

- Node.js 18+
- npm or yarn

### Installation

```bash
cd tests
npm install
```

### Configuration

Copy and edit the environment file:

```bash
cp .env.test .env
```

Environment variables:

```dotenv
API_BASE_URL=http://localhost:8000  # API server URL
API_VERSION=v1                       # API version prefix
TEST_TIMEOUT=30000                   # Test timeout in ms
MAX_RETRIES=3                        # Retry count for flaky tests
```

### Running Tests

```bash
# Run all tests
npm test

# Run tests in watch mode
npm run test:watch

# Run tests with coverage report
npm run test:coverage

# Run specific test file
npm run test:feature    # Feature store tests
npm run test:logging    # Logging module tests
npm run test:monitoring # Monitoring module tests
```

### Test Output

```
Test Suites: 3 passed, 3 total
Tests:       138 passed, 138 total
Snapshots:   0 total
Time:        1.76 s
```

## Custom Assertions

The test suite provides custom assertion utilities in `test/utils/assertions.js`:

```javascript
const { CustomAssertions: assert } = require('./utils');

// Response validation
assert.assertSuccessResponse(response, 200);
assert.assertValidationError(response, 'field_name');
assert.assertNotFound(response);

// Data structure validation
assert.assertFeatureStructure(feature);
assert.assertMetricStructure(metric);
assert.assertLogStructure(log);

// Data integrity
assert.assertMetricsHaveNoNegativeValues(metrics);
assert.assertTransactionRollback(logs, transactionId);
assert.assertValidUuid(id);
assert.assertValidIsoDate(timestamp);
```

## Test Design Principles

1. **Separation of Concerns**: Test data construction is completely separated from test logic using the Builder pattern
2. **Boundary Coverage**: Each module is tested at its boundaries (min/max values, edge cases, error conditions)
3. **Deterministic Testing**: Tests are designed to be repeatable and deterministic
4. **Readable Assertions**: Custom assertions make test intent clear and maintainable
5. **Graceful Degradation**: Tests detect when the API service is unavailable and skip gracefully

## Integration with CI/CD

The test suite can be integrated into CI/CD pipelines:

```yaml
# Example GitHub Actions workflow
- name: Run API Tests
  run: |
    cd tests
    npm ci
    npm test
```

## License

MIT
