import { GasEstimatorService } from '@core/usecases/gasEstimator.usecase';
import { MockLogger, MockCache } from '../__mocks__/mockPorts';
import type { GasEstimatorDependencies } from '@core/ports/gasEstimator.port';
import type { GasPriceHistory } from '@core/domain/blockchain';

describe('GasEstimatorService', () => {
  let gasEstimator: GasEstimatorService;
  let mockLogger: MockLogger;
  let mockCache: MockCache;
  let mockDeps: jest.Mocked<GasEstimatorDependencies>;

  beforeEach(() => {
    mockLogger = new MockLogger();
    mockCache = new MockCache();
    mockDeps = {
      getBlockNumber: jest.fn().mockResolvedValue(BigInt(1000000)),
      getBlock: jest.fn().mockResolvedValue({
        baseFeePerGas: BigInt('20000000000'),
        gasUsed: BigInt(15000000),
        gasLimit: BigInt(30000000),
      }),
      getFeePerGas: jest.fn().mockResolvedValue({
        baseFeePerGas: BigInt('20000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
      }),
      estimateGas: jest.fn().mockResolvedValue(BigInt(21000)),
      getHistoricalData: jest.fn().mockResolvedValue([]),
    };

    gasEstimator = new GasEstimatorService(
      mockDeps,
      mockLogger,
      mockCache,
      {
        defaultSpeed: 'standard',
        defaultBufferPercentage: 10,
        cacheTTL: 30,
      }
    );
  });

  describe('estimate', () => {
    it('should estimate gas with simple strategy', async () => {
      gasEstimator.setDefaultStrategy('simple');

      const result = await gasEstimator.estimate({
        chainId: 1,
        to: '0x0000000000000000000000000000000000000001',
        speed: 'standard',
      });

      expect(result).toBeDefined();
      expect(result.gasLimit).toBe(BigInt(21000));
      expect(result.baseFeePerGas).toBe(BigInt('20000000000'));
      expect(result.confidence).toBeGreaterThan(0);
      expect(mockDeps.getFeePerGas).toHaveBeenCalled();
      expect(mockDeps.estimateGas).toHaveBeenCalled();
    });

    it('should use cached estimate when available', async () => {
      const cachedEstimate = {
        gasLimit: BigInt(21000),
        baseFeePerGas: BigInt('20000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
        maxFeePerGas: BigInt('42000000000'),
        estimatedCost: BigInt('882000000000000'),
        confidence: 0.9,
        timestamp: new Date().toISOString(),
      };

      await mockCache.set('gas:estimate:1:0x0000000000000000000000000000000000000001:standard', cachedEstimate);

      const result = await gasEstimator.estimate({
        chainId: 1,
        to: '0x0000000000000000000000000000000000000001',
        speed: 'standard',
      });

      expect(result).toEqual(cachedEstimate);
      expect(mockDeps.getFeePerGas).not.toHaveBeenCalled();
    });

    it('should calculate different prices for different speeds', async () => {
      gasEstimator.setDefaultStrategy('simple');

      const [slow, standard, fast, instant] = await Promise.all([
        gasEstimator.estimate({ chainId: 1, speed: 'slow' }),
        gasEstimator.estimate({ chainId: 1, speed: 'standard' }),
        gasEstimator.estimate({ chainId: 1, speed: 'fast' }),
        gasEstimator.estimate({ chainId: 1, speed: 'instant' }),
      ]);

      expect(slow.maxPriorityFeePerGas).toBeLessThan(standard.maxPriorityFeePerGas);
      expect(standard.maxPriorityFeePerGas).toBeLessThan(fast.maxPriorityFeePerGas);
      expect(fast.maxPriorityFeePerGas).toBeLessThan(instant.maxPriorityFeePerGas);
    });
  });

  describe('getCurrentGasPrice', () => {
    it('should return tiered gas prices', async () => {
      const result = await gasEstimator.getCurrentGasPrice(1);

      expect(result).toHaveProperty('slow');
      expect(result).toHaveProperty('standard');
      expect(result).toHaveProperty('fast');
      expect(result).toHaveProperty('instant');
      expect(result.slow.maxPriorityFeePerGas).toBeLessThan(result.standard.maxPriorityFeePerGas);
    });
  });

  describe('suggestGasLimit', () => {
    it('should add buffer to estimated gas', () => {
      const result = gasEstimator.suggestGasLimit(1, BigInt(100000), 10);
      expect(result).toBe(BigInt(110000));
    });

    it('should use default buffer percentage when not specified', () => {
      const result = gasEstimator.suggestGasLimit(1, BigInt(100000));
      expect(result).toBe(BigInt(110000));
    });
  });

  describe('calculateTransactionCost', () => {
    it('should multiply gas limit by max fee per gas', () => {
      const cost = gasEstimator.calculateTransactionCost(BigInt(21000), BigInt('30000000000'));
      expect(cost).toBe(BigInt('630000000000000'));
    });
  });

  describe('shouldWaitForLowerGas', () => {
    it('should not wait for high urgency transactions', async () => {
      const estimate = {
        gasLimit: BigInt(21000),
        baseFeePerGas: BigInt('100000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
        maxFeePerGas: BigInt('102000000000'),
        estimatedCost: BigInt('2142000000000000'),
        confidence: 0.9,
        timestamp: new Date().toISOString(),
      };

      const result = await gasEstimator.shouldWaitForLowerGas(1, estimate, 'high');
      expect(result.shouldWait).toBe(false);
    });

    it('should suggest waiting for low urgency when price is high', async () => {
      const history: GasPriceHistory[] = Array.from({ length: 50 }, (_, i) => ({
        chainId: 1,
        timestamp: new Date(Date.now() - i * 60000).toISOString(),
        baseFeePerGas: BigInt('20000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
        gasUsedRatio: 0.5,
      }));

      mockDeps.getHistoricalData.mockResolvedValue(history);

      const estimate = {
        gasLimit: BigInt(21000),
        baseFeePerGas: BigInt('100000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
        maxFeePerGas: BigInt('102000000000'),
        estimatedCost: BigInt('2142000000000000'),
        confidence: 0.9,
        timestamp: new Date().toISOString(),
      };

      const result = await gasEstimator.shouldWaitForLowerGas(1, estimate, 'low');
      expect(result.shouldWait).toBe(true);
      expect(result.suggestedDelay).toBeGreaterThan(0);
    });
  });

  describe('estimation strategies', () => {
    it('should allow adding custom strategy', async () => {
      const customStrategy = {
        id: 'custom',
        name: 'Custom Strategy',
        description: 'Custom estimation',
        estimate: jest.fn().mockResolvedValue({
          gasLimit: BigInt(50000),
          baseFeePerGas: BigInt('10000000000'),
          maxPriorityFeePerGas: BigInt('1000000000'),
          maxFeePerGas: BigInt('21000000000'),
          estimatedCost: BigInt('1050000000000000'),
          confidence: 0.95,
          timestamp: new Date().toISOString(),
        }),
      };

      gasEstimator.addEstimationStrategy(customStrategy);
      gasEstimator.setDefaultStrategy('custom');

      const result = await gasEstimator.estimate({ chainId: 1 });
      expect(customStrategy.estimate).toHaveBeenCalled();
      expect(result.gasLimit).toBe(BigInt(50000));
    });

    it('should use history-weighted strategy by default', async () => {
      const history: GasPriceHistory[] = Array.from({ length: 30 }, (_, i) => ({
        chainId: 1,
        timestamp: new Date(Date.now() - i * 60000).toISOString(),
        baseFeePerGas: BigInt('20000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
        gasUsedRatio: 0.5,
      }));

      mockDeps.getHistoricalData.mockResolvedValue(history);

      const result = await gasEstimator.estimate({ chainId: 1 });
      expect(result.confidence).toBeGreaterThanOrEqual(0.5);
      expect(mockDeps.getHistoricalData).toHaveBeenCalled();
    });
  });
});
