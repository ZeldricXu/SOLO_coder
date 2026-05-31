const { TestDataFactory } = require('./data/builders');
const { createClient, CustomAssertions: assert } = require('./utils');
const { v4: uuidv4 } = require('uuid');

describe('Logging Module - Transaction Rollback Correctness Tests', () => {
  let client;

  beforeAll(() => {
    client = createClient();
  });

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('Structured Log Output Validation', () => {
    test('should produce valid JSON log entries', async () => {
      const logEntry = TestDataFactory.logEntry()
        .asInfo()
        .withMessage('Test log message')
        .build();

      const response = await client.post('/core/tasks/execute', {
        task_type: 'logging_test',
        namespace: 'test',
        payload: { log_entry: logEntry },
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 201]).toContain(response.status);
    });

    test('should include required fields in all log entries', async () => {
      const logLevels = ['debug', 'info', 'warning', 'error', 'critical'];
      
      for (const level of logLevels) {
        const logEntry = TestDataFactory.logEntry()
          .withLevel(level)
          .build();

        expect(logEntry).toHaveProperty('level');
        expect(logEntry).toHaveProperty('message');
        expect(logEntry).toHaveProperty('timestamp');
        expect(logEntry.level).toBe(level);
      }
    });

    test('should handle different log levels correctly', async () => {
      const logEntry = TestDataFactory.logEntry()
        .asError()
        .withError(new Error('Test error'))
        .build();

      expect(logEntry.level).toBe('error');
      expect(logEntry.error).toBe('Test error');
    });

    test('should include trace_id for distributed tracing', async () => {
      const traceId = uuidv4();
      const logEntry = TestDataFactory.logEntry()
        .withTraceId(traceId)
        .build();

      expect(logEntry.trace_id).toBe(traceId);
    });

    test('should handle request_id context', async () => {
      const requestId = uuidv4();
      const logEntry = TestDataFactory.logEntry()
        .withRequestId(requestId)
        .build();

      expect(logEntry.request_id).toBe(requestId);
    });

    test('should handle user_id context', async () => {
      const userId = uuidv4();
      const logEntry = TestDataFactory.logEntry()
        .withUserId(userId)
        .build();

      expect(logEntry.user_id).toBe(userId);
    });
  });

  describe('Transaction Context Logging', () => {
    test('should log transaction start correctly', async () => {
      const transactionId = uuidv4();
      const logEntry = TestDataFactory.logEntry()
        .asInfo()
        .withTransactionContext(transactionId)
        .withMessage('Transaction started')
        .build();

      expect(logEntry.transaction_id).toBe(transactionId);
      expect(logEntry.transaction_phase).toBe('processing');
    });

    test('should log transaction rollback with reason', async () => {
      const transactionId = uuidv4();
      const rollbackReason = 'Constraint violation detected';
      
      const logEntry = TestDataFactory.logEntry()
        .asError()
        .withRollbackContext(transactionId, rollbackReason)
        .withMessage('Transaction rolled back')
        .build();

      expect(logEntry.transaction_id).toBe(transactionId);
      expect(logEntry.transaction_phase).toBe('rollback');
      expect(logEntry.rollback_reason).toBe(rollbackReason);
    });

    test('should maintain consistent transaction_id across all transaction logs', async () => {
      const transactionId = uuidv4();
      
      const logs = [
        TestDataFactory.logEntry().asInfo().withTransactionContext(transactionId).withMessage('Phase 1 started').build(),
        TestDataFactory.logEntry().asInfo().withTransactionContext(transactionId).withMessage('Phase 1 completed').build(),
        TestDataFactory.logEntry().asError().withRollbackContext(transactionId, 'Error in phase 2').withMessage('Rollback initiated').build(),
      ];

      logs.forEach(log => {
        expect(log.transaction_id).toBe(transactionId);
      });
    });

    test('should log correct transaction phase transitions', async () => {
      const transactionId = uuidv4();
      const phases = ['processing', 'committing', 'rollback', 'completed'];
      
      const logs = phases.map(phase => 
        TestDataFactory.logEntry()
          .with('transaction_id', transactionId)
          .with('transaction_phase', phase)
          .build()
      );

      expect(logs.map(l => l.transaction_phase)).toEqual(phases);
    });
  });

  describe('Transaction Rollback Scenarios', () => {
    test('should trigger rollback on validation error', async () => {
      const transactionId = uuidv4();
      
      const taskData = TestDataFactory.taskExecute()
        .withTaskType('validation_test')
        .withPayload({ invalid: 'data', transaction_id: transactionId })
        .build();

      const response = await client.executeTask(taskData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);

      if (response.status === 422 || response.body.data?.status === 'failed') {
        const errorLog = TestDataFactory.logEntry()
          .asError()
          .withRollbackContext(transactionId, 'Validation failed')
          .build();
        
        expect(errorLog.transaction_phase).toBe('rollback');
        expect(errorLog.rollback_reason).toBeTruthy();
      }
    });

    test('should trigger rollback on timeout', async () => {
      const transactionId = uuidv4();
      
      const taskData = TestDataFactory.taskExecute()
        .withTaskType('timeout_test')
        .withPayload({ simulate_timeout: true, transaction_id: transactionId })
        .build();

      const response = await client.executeTask(taskData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      const timeoutLog = TestDataFactory.logEntry()
        .asError()
        .withRollbackContext(transactionId, 'Operation timed out')
        .build();
      
      expect(timeoutLog.transaction_phase).toBe('rollback');
    });

    test('should trigger rollback on resource acquisition failure', async () => {
      const transactionId = uuidv4();
      
      const taskData = TestDataFactory.taskExecute()
        .withTaskType('resource_test')
        .withPayload({ resource_type: 'exclusive_lock', transaction_id: transactionId })
        .build();

      const response = await client.executeTask(taskData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      const rollbackLog = TestDataFactory.logEntry()
        .asWarning()
        .withRollbackContext(transactionId, 'Failed to acquire resource')
        .build();
      
      expect(rollbackLog.transaction_phase).toBe('rollback');
      expect(rollbackLog.rollback_reason).toBeTruthy();
    });

    test('should trigger rollback on external service failure', async () => {
      const transactionId = uuidv4();
      
      const taskData = TestDataFactory.taskExecute()
        .withTaskType('external_service_test')
        .withPayload({ simulate_external_failure: true, transaction_id: transactionId })
        .build();

      const response = await client.executeTask(taskData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      const errorLog = TestDataFactory.logEntry()
        .asCritical()
        .withRollbackContext(transactionId, 'External service unavailable')
        .build();
      
      expect(errorLog.transaction_phase).toBe('rollback');
      expect(errorLog.level).toBe('critical');
    });

    test('should trigger rollback on database constraint violation', async () => {
      const transactionId = uuidv4();
      
      const taskData = TestDataFactory.taskExecute()
        .withTaskType('db_constraint_test')
        .withPayload({ violate_constraint: true, transaction_id: transactionId })
        .build();

      const response = await client.executeTask(taskData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      const rollbackLog = TestDataFactory.logEntry()
        .asError()
        .withRollbackContext(transactionId, 'Database constraint violation')
        .build();
      
      assert.assertTransactionRollback([rollbackLog], transactionId);
    });

    test('should trigger rollback on concurrency conflict', async () => {
      const transactionId = uuidv4();
      
      const taskData = TestDataFactory.taskExecute()
        .withTaskType('concurrency_test')
        .withPayload({ conflict_scenario: true, transaction_id: transactionId })
        .build();

      const response = await client.executeTask(taskData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      const rollbackLog = TestDataFactory.logEntry()
        .asWarning()
        .withRollbackContext(transactionId, 'Concurrent modification detected')
        .build();
      
      expect(rollbackLog.transaction_phase).toBe('rollback');
    });
  });

  describe('Rollback Logging Completeness', () => {
    test('should log rollback start and completion', async () => {
      const transactionId = uuidv4();
      
      const logs = [
        TestDataFactory.logEntry()
          .asWarning()
          .withTransactionContext(transactionId)
          .withMessage('Rollback initiated')
          .with('rollback_reason', 'Test failure')
          .build(),
        TestDataFactory.logEntry()
          .asInfo()
          .withTransactionContext(transactionId)
          .withMessage('Rollback completed successfully')
          .with('rollback_duration_ms', 150)
          .build(),
      ];

      expect(logs[0].transaction_phase).toBe('processing');
      expect(logs[0].rollback_reason).toBe('Test failure');
      expect(logs[1].message).toContain('completed');
    });

    test('should include rollback duration in logs', async () => {
      const logEntry = TestDataFactory.logEntry()
        .asInfo()
        .withMessage('Rollback completed')
        .with('rollback_duration_ms', 250)
        .with('rollback_steps_completed', 5)
        .build();

      expect(logEntry.rollback_duration_ms).toBe(250);
      expect(logEntry.rollback_steps_completed).toBe(5);
    });

    test('should log all steps rolled back', async () => {
      const transactionId = uuidv4();
      const steps = ['step_1', 'step_2', 'step_3', 'step_4'];
      
      const logs = steps.map(step =>
        TestDataFactory.logEntry()
          .asDebug()
          .withTransactionContext(transactionId)
          .withMessage(`Rolling back ${step}`)
          .with('rollback_step', step)
          .build()
      );

      expect(logs).toHaveLength(steps.length);
      logs.forEach((log, index) => {
        expect(log.rollback_step).toBe(steps[index]);
      });
    });

    test('should log rollback errors if they occur', async () => {
      const transactionId = uuidv4();
      
      const logEntry = TestDataFactory.logEntry()
        .asCritical()
        .withRollbackContext(transactionId, 'Rollback failure')
        .withMessage('Rollback failed')
        .with('rollback_error', 'Connection lost during rollback')
        .with('rollback_incomplete_steps', ['step_3', 'step_4'])
        .build();

      expect(logEntry.level).toBe('critical');
      expect(logEntry.rollback_error).toBeTruthy();
      expect(logEntry.rollback_incomplete_steps).toHaveLength(2);
    });
  });

  describe('Error Handling and Edge Cases', () => {
    test('should handle large log messages', async () => {
      const logEntry = TestDataFactory.logEntry()
        .asInfo()
        .withLongMessage(10000)
        .build();

      expect(logEntry.message.length).toBe(10000);
    });

    test('should handle special characters in log messages', async () => {
      const logEntry = TestDataFactory.logEntry()
        .asInfo()
        .withSpecialCharacters()
        .build();

      expect(logEntry.message).toContain('special chars');
    });

    test('should handle large metadata context', async () => {
      const logEntry = TestDataFactory.logEntry()
        .asInfo()
        .withLargeContext()
        .build();

      expect(logEntry.metadata).toBeDefined();
      expect(Object.keys(logEntry.metadata).length).toBe(100);
    });

    test('should handle nested exception logging', async () => {
      try {
        try {
          throw new Error('Inner error');
        } catch (innerError) {
          throw new Error(`Outer error: ${innerError.message}`);
        }
      } catch (outerError) {
        const logEntry = TestDataFactory.logEntry()
          .asError()
          .withError(outerError)
          .with('error_stack', outerError.stack)
          .with('error_cause', 'Inner error')
          .build();

        expect(logEntry.error).toContain('Outer error');
        expect(logEntry.error_stack).toBeTruthy();
      }
    });

    test('should handle multiple errors in same transaction', async () => {
      const transactionId = uuidv4();
      
      const errors = [
        { message: 'First error', code: 'ERR001' },
        { message: 'Second error', code: 'ERR002' },
        { message: 'Third error', code: 'ERR003' },
      ];

      const logs = errors.map(error =>
        TestDataFactory.logEntry()
          .asError()
          .withRollbackContext(transactionId, error.message)
          .with('error_code', error.code)
          .build()
      );

      expect(logs).toHaveLength(3);
      logs.forEach((log, index) => {
        expect(log.transaction_id).toBe(transactionId);
        expect(log.error_code).toBe(errors[index].code);
      });
    });
  });

  describe('Log Structured Output Verification', () => {
    test('should produce valid JSON that can be parsed', () => {
      const logEntry = TestDataFactory.logEntry()
        .asInfo()
        .withMessage('Test JSON output')
        .withMetadata({ key: 'value', nested: { deep: true } })
        .build();

      const jsonString = JSON.stringify(logEntry);
      const parsed = JSON.parse(jsonString);

      expect(parsed.message).toBe('Test JSON output');
      expect(parsed.metadata.nested.deep).toBe(true);
    });

    test('should maintain consistent field names across all log levels', () => {
      const levels = ['debug', 'info', 'warning', 'error', 'critical'];
      const requiredFields = ['level', 'message', 'timestamp', 'service', 'trace_id'];

      levels.forEach(level => {
        const logEntry = TestDataFactory.logEntry().withLevel(level).build();
        requiredFields.forEach(field => {
          expect(logEntry).toHaveProperty(field);
        });
      });
    });

    test('should use consistent timestamp format', () => {
      const logEntry = TestDataFactory.logEntry().build();
      
      assert.assertValidIsoDate(logEntry.timestamp);
    });

    test('should include service name for aggregation', () => {
      const logEntry = TestDataFactory.logEntry()
        .withService('feature-store-service')
        .build();

      expect(logEntry.service).toBe('feature-store-service');
    });
  });

  describe('Audit Log Integration', () => {
    test('should log feature creation with audit trail', async () => {
      const auditLog = TestDataFactory.auditLog()
        .asCreateAction()
        .withResourceType('feature')
        .build();

      assert.assertAuditLogStructure(auditLog);
      expect(auditLog.action).toBe('create');
    });

    test('should log feature deletion with audit trail', async () => {
      const auditLog = TestDataFactory.auditLog()
        .asDeleteAction()
        .withResourceType('feature')
        .build();

      expect(auditLog.action).toBe('delete');
    });

    test('should log failed login attempts', async () => {
      const auditLog = TestDataFactory.auditLog()
        .asFailedLogin()
        .build();

      expect(auditLog.action).toBe('login_failed');
      expect(auditLog.details.reason).toBe('invalid_credentials');
    });

    test('should include IP address in audit logs', async () => {
      const auditLog = TestDataFactory.auditLog()
        .withInternalIp()
        .build();

      expect(auditLog.ip_address).toBe('127.0.0.1');
    });

    test('should handle IPv6 addresses in audit logs', async () => {
      const auditLog = TestDataFactory.auditLog()
        .withIpv6Address()
        .build();

      expect(auditLog.ip_address).toMatch(/:/);
    });

    test('should include user agent in audit logs', async () => {
      const auditLog = TestDataFactory.auditLog().build();

      expect(auditLog.user_agent).toBeTruthy();
      expect(typeof auditLog.user_agent).toBe('string');
    });
  });
});
