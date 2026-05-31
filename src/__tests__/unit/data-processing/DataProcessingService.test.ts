import { DefaultDataProcessingService } from '../../../modules/data-processing';
import { createContext, ValidationError } from '../../../common';
import { DataRecordBuilder } from '../../builders';
import { ProcessingPipeline, ProcessingRule } from '../../../core/ports';

describe('DefaultDataProcessingService', () => {
  let service: DefaultDataProcessingService;
  let ctx: ReturnType<typeof createContext>;

  beforeEach(() => {
    service = new DefaultDataProcessingService();
    ctx = createContext('test-namespace');
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  describe('Pipeline Management', () => {
    it('should create a new pipeline', async () => {
      const rules: ProcessingRule[] = [
        {
          id: 'rule-1',
          name: 'trim-strings',
          type: 'transform',
          config: { transformerType: 'stringTrimmer' },
          enabled: true
        }
      ];

      const pipelineId = await service.createPipeline({
        name: 'test-pipeline',
        rules
      });

      expect(pipelineId).toBeDefined();
      expect(pipelineId.startsWith('pipe_')).toBe(true);

      const pipeline = await service.getPipeline(pipelineId);
      expect(pipeline).not.toBeNull();
      expect(pipeline?.name).toBe('test-pipeline');
      expect(pipeline?.version).toBe(1);
      expect(pipeline?.rules).toEqual(rules);
    });

    it('should return null for non-existent pipeline', async () => {
      const pipeline = await service.getPipeline('non-existent-id');
      expect(pipeline).toBeNull();
    });

    it('should update existing pipeline', async () => {
      const pipelineId = await service.createPipeline({
        name: 'old-name',
        rules: []
      });

      const updated = await service.updatePipeline(pipelineId, { name: 'new-name' });

      expect(updated).not.toBeNull();
      expect(updated?.name).toBe('new-name');
      expect(updated?.version).toBe(2);
    });

    it('should list all pipelines', async () => {
      await service.createPipeline({ name: 'pipeline-1', rules: [] });
      await service.createPipeline({ name: 'pipeline-2', rules: [] });

      const pipelines = await service.listPipelines();
      expect(pipelines.length).toBe(2);
    });

    it('should delete pipeline', async () => {
      const pipelineId = await service.createPipeline({ name: 'to-delete', rules: [] });

      const result = await service.deletePipeline(pipelineId);
      expect(result).toBe(true);

      const pipeline = await service.getPipeline(pipelineId);
      expect(pipeline).toBeNull();
    });
  });

  describe('Normal Processing Flow', () => {
    let testPipelineId: string;

    beforeEach(async () => {
      const rules: ProcessingRule[] = [
        {
          id: 'trim',
          name: 'trim-fields',
          type: 'transform',
          config: { transformerType: 'stringTrimmer' },
          enabled: true
        },
        {
          id: 'lowercase',
          name: 'lowercase-email',
          type: 'transform',
          config: { transformerType: 'lowercase', fields: ['email'] },
          enabled: true
        },
        {
          id: 'normalize',
          name: 'normalize-basic',
          type: 'normalize',
          config: { normalizerType: 'basic', schema: {} },
          enabled: true
        }
      ];

      testPipelineId = await service.createPipeline({
        name: 'user-processing',
        rules
      });
    });

    it('should process valid records successfully', async () => {
      const records = [
        DataRecordBuilder.createUserRecord({
          userName: '  John Doe  ',
          email: '  JOHN@EXAMPLE.COM  '
        }),
        DataRecordBuilder.createUserRecord({
          userName: 'Jane Smith',
          email: 'jane@example.com'
        })
      ];

      const result = await service.process(ctx, records, testPipelineId);

      expect(result.success).toBe(true);
      expect(result.records.length).toBe(2);
      expect(result.errors.length).toBe(0);
      expect(result.startTime).toBeLessThanOrEqual(result.endTime);

      const firstRecord = result.records[0];
      expect(firstRecord.transformedData).toBeDefined();

      const transformed = firstRecord.transformedData as Record<string, unknown>;
      expect(transformed.userName).toBe('John Doe');
      expect(transformed.email).toBe('john@example.com');
    });

    it('should handle batch processing', async () => {
      const records = DataRecordBuilder.createBatch(10);
      const result = await service.process(ctx, records, testPipelineId);

      expect(result.success).toBe(true);
      expect(result.records.length).toBe(10);
      expect(result.errors.length).toBe(0);
    });

    it('should respect disabled rules', async () => {
      const pipelineWithDisabledRule = await service.createPipeline({
        name: 'disabled-rule-test',
        rules: [
          {
            id: 'disabled-rule',
            name: 'disabled',
            type: 'transform',
            config: { transformerType: 'stringTrimmer' },
            enabled: false
          }
        ]
      });

      const records = [DataRecordBuilder.createUserRecord({ userName: '  Test  ' })];
      const result = await service.process(ctx, records, pipelineWithDisabledRule);

      expect(result.success).toBe(true);
    });
  });

  describe('Error Handling', () => {
    it('should throw error when pipeline not found', async () => {
      const records = [DataRecordBuilder.createUserRecord()];

      await expect(service.process(ctx, records, 'non-existent-pipeline')).rejects.toThrow(ValidationError);
    });

    it('should handle invalid records gracefully', async () => {
      const pipelineId = await service.createPipeline({
        name: 'error-test',
        rules: [
          {
            id: 'filter-rule',
            name: 'filter-users',
            type: 'filter',
            config: {
              condition: (data: unknown) => {
                const d = data as Record<string, unknown>;
                return d.userId !== null && d.userId !== undefined;
              }
            },
            enabled: true
          }
        ]
      });

      const records = [
        DataRecordBuilder.createUserRecord(),
        DataRecordBuilder.createInvalidRecord()
      ];

      const result = await service.process(ctx, records, pipelineId);

      expect(result.success).toBe(false);
      expect(result.records.length).toBe(1);
      expect(result.errors.length).toBe(1);
      expect(result.errors[0].message).toBe('Record filtered out');
    });

    it('should throw error for unknown transformer type', async () => {
      const pipelineId = await service.createPipeline({
        name: 'unknown-transformer',
        rules: [
          {
            id: 'unknown',
            name: 'unknown-transform',
            type: 'transform',
            config: { transformerType: 'non_existent_transformer' },
            enabled: true
          }
        ]
      });

      const records = [DataRecordBuilder.createUserRecord()];
      const result = await service.process(ctx, records, pipelineId);

      expect(result.success).toBe(false);
      expect(result.errors.length).toBe(1);
      expect(result.errors[0].message).toContain('Transformer not found');
    });

    it('should continue processing when some records fail', async () => {
      const pipelineId = await service.createPipeline({
        name: 'partial-failure',
        rules: [
          {
            id: 'validate',
            name: 'validate-age',
            type: 'filter',
            config: {
              condition: (data: unknown) => {
                const d = data as Record<string, unknown>;
                return typeof d.age === 'number' && d.age >= 18;
              }
            },
            enabled: true
          }
        ]
      });

      const records = [
        DataRecordBuilder.createUserRecord({ age: 25 }),
        DataRecordBuilder.createUserRecord({ age: 15 }),
        DataRecordBuilder.createUserRecord({ age: 30 })
      ];

      const result = await service.process(ctx, records, pipelineId);

      expect(result.success).toBe(false);
      expect(result.records.length).toBe(2);
      expect(result.errors.length).toBe(1);
    });
  });

  describe('Configuration Management', () => {
    it('should set and retrieve configuration', async () => {
      const params = {
        timeout: 60000,
        maxBatchSize: 200,
        retryCount: 5
      };

      const config = await service.setConfig('test-namespace', params);

      expect(config.namespace).toBe('test-namespace');
      expect(config.parameters.timeout).toBe(60000);
      expect(config.parameters.maxBatchSize).toBe(200);
      expect(config.version).toBe(1);
    });

    it('should increment version on update', async () => {
      await service.setConfig('version-test', { param1: 'value1' });
      const config = await service.setConfig('version-test', { param2: 'value2' });

      expect(config.version).toBe(2);
      expect(config.parameters.param1).toBe('value1');
      expect(config.parameters.param2).toBe('value2');
    });
  });

  describe('Record Validation', () => {
    it('should validate records against schema', async () => {
      const schema = {
        type: 'object',
        properties: {
          userId: { type: 'string' },
          email: { type: 'string', format: 'email' },
          age: { type: 'number', minimum: 0 }
        },
        required: ['userId', 'email']
      };

      const records = [
        DataRecordBuilder.createUserRecord(),
        DataRecordBuilder.createInvalidRecord()
      ];

      const result = await service.validateRecords(records, schema);

      expect(result.valid.length).toBeGreaterThanOrEqual(1);
      expect(result.invalid.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('Custom Transformers', () => {
    it('should register and use custom transformer', async () => {
      const customTransformer = {
        transform: jest.fn().mockImplementation(async (data: unknown) => {
          const d = data as Record<string, unknown>;
          return { ...d, customField: 'transformed' };
        })
      };

      service.registerTransformer('custom_transformer', customTransformer);

      const pipelineId = await service.createPipeline({
        name: 'custom-transformer-pipeline',
        rules: [
          {
            id: 'custom',
            name: 'custom-rule',
            type: 'transform',
            config: { transformerType: 'custom_transformer' },
            enabled: true
          }
        ]
      });

      const records = [DataRecordBuilder.createUserRecord()];
      const result = await service.process(ctx, records, pipelineId);

      expect(result.success).toBe(true);
      expect(customTransformer.transform).toHaveBeenCalled();

      const transformed = result.records[0].transformedData as Record<string, unknown>;
      expect(transformed.customField).toBe('transformed');
    });
  });
});
