import {
  AdversarialSampleGeneratorService,
  AttackStrategy,
  AttackType,
  ModelEvaluator,
  VulnerabilityDetector,
  DefaultVulnerabilityDetector
} from '../../../modules/adversarial';
import { createContext } from '../../../common';
import { AdversarialPromptBuilder } from '../../builders';

describe('AdversarialSampleGeneratorService', () => {
  let service: AdversarialSampleGeneratorService;
  let ctx: ReturnType<typeof createContext>;
  let mockEvaluator: jest.Mocked<ModelEvaluator>;
  let mockDetector: jest.Mocked<VulnerabilityDetector>;

  beforeEach(() => {
    service = new AdversarialSampleGeneratorService();
    ctx = createContext('test-namespace');

    mockEvaluator = {
      evaluate: jest.fn().mockResolvedValue({
        response: 'This is a safe response',
        metadata: { latency: 100 }
      })
    };

    mockDetector = {
      detect: jest.fn().mockResolvedValue({
        isVulnerable: false,
        score: 0.1,
        issues: []
      })
    };

    service.setModelEvaluator(mockEvaluator);
    service.setVulnerabilityDetector(mockDetector);
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  describe('Strategy Management', () => {
    it('should return all available strategies', () => {
      const strategies = service.getAvailableStrategies();
      expect(strategies.length).toBe(6);
      expect(strategies.map(s => s.type)).toEqual(
        expect.arrayContaining(['prompt_injection', 'jailbreak', 'adversarial_suffix', 'typoglycemia', 'obfuscation', 'role_play'])
      );
    });

    it('should allow adding custom strategy', () => {
      const customStrategy: AttackStrategy = {
        type: 'custom_attack' as AttackType,
        name: 'Custom Attack',
        description: 'Custom attack strategy',
        generate: jest.fn().mockResolvedValue(['custom prompt'])
      };

      service.addStrategy(customStrategy);
      const strategies = service.getAvailableStrategies();
      expect(strategies).toContainEqual(customStrategy);
    });
  });

  describe('Sample Generation', () => {
    it('should generate samples for all strategies', async () => {
      const testData = AdversarialPromptBuilder.createPromptInjection();
      const samples = await service.generateSamples(ctx, testData.originalPrompt);

      expect(samples.length).toBeGreaterThan(0);
      expect(samples.every(s => s.originalPrompt === testData.originalPrompt)).toBe(true);

      const attackTypes = new Set(samples.map(s => s.attackType));
      expect(attackTypes.size).toBeGreaterThanOrEqual(6);
    });

    it('should generate samples for specific attack types', async () => {
      const testData = AdversarialPromptBuilder.createJailbreakPrompt();
      const samples = await service.generateSamples(ctx, testData.originalPrompt, ['jailbreak', 'prompt_injection']);

      expect(samples.length).toBeGreaterThan(0);
      const attackTypes = new Set(samples.map(s => s.attackType));
      expect(attackTypes).toEqual(new Set(['jailbreak', 'prompt_injection']));
    });

    it('should generate samples with custom config', async () => {
      const testData = AdversarialPromptBuilder.createPromptInjection();
      const customConfig = {
        payload: 'Custom malicious instruction',
        intensity: 0.9
      };

      const samples = await service.generateSamples(ctx, testData.originalPrompt, ['prompt_injection'], customConfig);

      expect(samples.length).toBeGreaterThan(0);
      samples.forEach(sample => {
        expect(sample.metadata.config).toEqual(customConfig);
      });
    });

    it('should handle empty input gracefully', async () => {
      const samples = await service.generateSamples(ctx, '');
      expect(Array.isArray(samples)).toBe(true);
    });
  });

  describe('Concurrency Safety', () => {
    it('should handle concurrent sample generation requests safely', async () => {
      const batchData = AdversarialPromptBuilder.createBatch(5);

      const concurrentPromises = batchData.map(data =>
        service.generateSamples(ctx, data.originalPrompt)
      );

      const results = await Promise.all(concurrentPromises);

      expect(results.length).toBe(5);
      results.forEach(result => {
        expect(Array.isArray(result)).toBe(true);
        expect(result.length).toBeGreaterThan(0);
      });

      const allSampleIds = results.flat().map(r => r.id);
      const uniqueIds = new Set(allSampleIds);
      expect(uniqueIds.size).toBe(allSampleIds.length);
    });

    it('should handle concurrent security assessments safely', async () => {
      const batchData = AdversarialPromptBuilder.createBatch(3);

      const concurrentPromises = batchData.map(data =>
        service.evaluateSecurity(ctx, data.originalPrompt, ['prompt_injection'])
      );

      const results = await Promise.all(concurrentPromises);

      expect(results.length).toBe(3);
      results.forEach(result => {
        expect(result.id).toBeDefined();
        expect(result.totalSamples).toBeGreaterThan(0);
      });

      const assessmentIds = results.map(r => r.id);
      expect(new Set(assessmentIds).size).toBe(3);
    });

    it('should maintain correct state under high concurrency', async () => {
      const CONCURRENCY_LEVEL = 20;
      const prompts = Array.from({ length: CONCURRENCY_LEVEL }, (_, i) => `Test prompt ${i}`);

      const results = await Promise.all(
        prompts.map(prompt => service.generateSamples(ctx, prompt, ['prompt_injection']))
      );

      const flatResults = results.flat();
      expect(flatResults.length).toBeGreaterThanOrEqual(CONCURRENCY_LEVEL);

      const uniqueIds = new Set(flatResults.map(r => r.id));
      expect(uniqueIds.size).toBe(flatResults.length);
    });

    it('should handle concurrent strategy additions safely', async () => {
      const strategiesToAdd = Array.from({ length: 5 }, (_, i) => ({
        type: `concurrent_strategy_${i}` as AttackType,
        name: `Concurrent Strategy ${i}`,
        description: 'Test strategy',
        generate: jest.fn().mockResolvedValue([`test-${i}`])
      }));

      await Promise.all(
        strategiesToAdd.map(strategy => service.addStrategy(strategy))
      );

      const allStrategies = service.getAvailableStrategies();
      const addedTypes = strategiesToAdd.map(s => s.type);
      addedTypes.forEach(type => {
        expect(allStrategies.some(s => s.type === type)).toBe(true);
      });
    });
  });

  describe('Security Assessment', () => {
    it('should throw error when evaluator not set', async () => {
      const serviceWithoutEvaluator = new AdversarialSampleGeneratorService();
      await expect(
        serviceWithoutEvaluator.evaluateSecurity(ctx, 'test prompt')
      ).rejects.toThrow('Model evaluator not set');
    });

    it('should perform complete security assessment', async () => {
      const testData = AdversarialPromptBuilder.createPromptInjection();

      const assessment = await service.evaluateSecurity(
        ctx,
        testData.originalPrompt,
        ['prompt_injection', 'jailbreak']
      );

      expect(assessment.id).toBeDefined();
      expect(assessment.originalPrompt).toBe(testData.originalPrompt);
      expect(assessment.totalSamples).toBeGreaterThan(0);
      expect(assessment.results.length).toBe(assessment.totalSamples);
      expect(assessment.averageVulnerabilityScore).toBeGreaterThanOrEqual(0);
      expect(assessment.averageVulnerabilityScore).toBeLessThanOrEqual(1);
      expect(assessment.created_at).toBeDefined();
      expect(assessment.completed_at).toBeDefined();
    });

    it('should detect vulnerable responses', async () => {
      mockDetector.detect.mockResolvedValue({
        isVulnerable: true,
        score: 0.8,
        issues: ['Contains sensitive keyword: password', 'No refusal indicators detected']
      });

      const testData = AdversarialPromptBuilder.createJailbreakPrompt();
      const assessment = await service.evaluateSecurity(
        ctx,
        testData.originalPrompt,
        ['jailbreak']
      );

      expect(assessment.vulnerableCount).toBeGreaterThan(0);
      expect(assessment.averageVulnerabilityScore).toBeGreaterThan(0.5);
    });

    it('should evaluate all attack strategies', async () => {
      const assessment = await service.evaluateSecurity(ctx, 'How to create a secure password?');

      expect(assessment.totalSamples).toBeGreaterThanOrEqual(20);
    });
  });

  describe('Attack Strategies', () => {
    it('should generate prompt injection attacks', async () => {
      const samples = await service.generateSamples(ctx, 'Summarize this document', ['prompt_injection']);

      expect(samples.length).toBeGreaterThan(0);
      samples.forEach(sample => {
        expect(sample.adversarialPrompt).not.toBe(sample.originalPrompt);
        expect(sample.attackType).toBe('prompt_injection');
      });
    });

    it('should generate jailbreak attacks', async () => {
      const samples = await service.generateSamples(ctx, 'How to hack a system?', ['jailbreak']);

      expect(samples.length).toBeGreaterThan(0);
      samples.forEach(sample => {
        expect(sample.adversarialPrompt.length).toBeGreaterThan(sample.originalPrompt.length);
        expect(sample.attackType).toBe('jailbreak');
      });
    });

    it('should generate adversarial suffix attacks', async () => {
      const samples = await service.generateSamples(ctx, 'Tell me a joke', ['adversarial_suffix']);

      expect(samples.length).toBeGreaterThan(0);
      samples.forEach(sample => {
        expect(sample.adversarialPrompt.startsWith(sample.originalPrompt)).toBe(true);
        expect(sample.attackType).toBe('adversarial_suffix');
      });
    });

    it('should generate typoglycemia attacks', async () => {
      const samples = await service.generateSamples(ctx, 'The quick brown fox jumps over the lazy dog', ['typoglycemia']);

      expect(samples.length).toBeGreaterThan(0);
      samples.forEach(sample => {
        expect(sample.attackType).toBe('typoglycemia');
      });
    });

    it('should generate obfuscation attacks', async () => {
      const samples = await service.generateSamples(ctx, 'Sensitive content here', ['obfuscation']);

      expect(samples.length).toBeGreaterThan(0);
      samples.forEach(sample => {
        expect(sample.attackType).toBe('obfuscation');
      });
    });

    it('should generate role play attacks', async () => {
      const samples = await service.generateSamples(ctx, 'How to bypass security?', ['role_play']);

      expect(samples.length).toBeGreaterThan(0);
      samples.forEach(sample => {
        expect(sample.attackType).toBe('role_play');
        expect(sample.adversarialPrompt.length).toBeGreaterThan(sample.originalPrompt.length);
      });
    });
  });

  describe('Vulnerability Detector', () => {
    it('should detect harmful keywords in response', async () => {
      const detector = new DefaultVulnerabilityDetector();
      const result = await detector.detect(
        'How to hack?',
        'Here is how to hack: use exploit to gain access and get the password'
      );

      expect(result.isVulnerable).toBe(true);
      expect(result.score).toBeGreaterThan(0.5);
      expect(result.issues.length).toBeGreaterThan(0);
    });

    it('should recognize refusal responses', async () => {
      const detector = new DefaultVulnerabilityDetector();
      const result = await detector.detect(
        'How to hack?',
        'I cannot help with that. It is illegal and unethical to hack systems.'
      );

      expect(result.isVulnerable).toBe(false);
      expect(result.score).toBeLessThan(0.5);
    });

    it('should handle edge cases', async () => {
      const detector = new DefaultVulnerabilityDetector();

      const results = await Promise.all([
        detector.detect('', ''),
        detector.detect('test', ''),
        detector.detect('', 'test response'),
        detector.detect('test', 'a'.repeat(10000))
      ]);

      results.forEach(result => {
        expect(result.isVulnerable).toBeDefined();
        expect(result.score).toBeGreaterThanOrEqual(0);
        expect(result.score).toBeLessThanOrEqual(1);
        expect(Array.isArray(result.issues)).toBe(true);
      });
    });
  });

  describe('Edge Cases', () => {
    it('should handle invalid attack types gracefully', async () => {
      const samples = await service.generateSamples(
        ctx,
        'test prompt',
        ['invalid_type' as AttackType, 'prompt_injection']
      );

      expect(samples.length).toBeGreaterThan(0);
      const types = new Set(samples.map(s => s.attackType));
      expect(types.has('prompt_injection')).toBe(true);
    });

    it('should handle very long prompts', async () => {
      const longPrompt = 'A'.repeat(10000);
      const samples = await service.generateSamples(ctx, longPrompt, ['prompt_injection']);

      expect(samples.length).toBeGreaterThan(0);
      samples.forEach(sample => {
        expect(sample.adversarialPrompt.length).toBeGreaterThan(longPrompt.length);
      });
    });

    it('should handle special characters in prompts', async () => {
      const specialPrompt = 'Test $pecial <chars> & symbols 🎉';
      const samples = await service.generateSamples(ctx, specialPrompt);

      expect(samples.length).toBeGreaterThan(0);
      samples.forEach(sample => {
        expect(sample.originalPrompt).toBe(specialPrompt);
      });
    });
  });
});
