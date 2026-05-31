const { GasDataBuilder } = require('../builders');

describe('Gas Estimator Module - Data Consistency', () => {
  let gasBuilder;

  beforeEach(() => {
    gasBuilder = new GasDataBuilder();
  });

  describe('Data Generation', () => {
    it('should generate consistent gas price distribution', () => {
      const prices = gasBuilder
        .withBaseFee(30_000_000_000)
        .generateGasPriceDistribution(0.3);

      expect(Array.isArray(prices)).toBe(true);
      expect(prices.length).toBe(1000);
      expect(prices.every(p => typeof p === 'number')).toBe(true);

      const sorted = [...prices].sort((a, b) => a - b);
      expect(prices).toEqual(sorted);
    });

    it('should respect volatility parameter', () => {
      const lowVolatility = gasBuilder
        .withBaseFee(30_000_000_000)
        .generateGasPriceDistribution(0.1);

      const highVolatility = gasBuilder
        .withBaseFee(30_000_000_000)
        .generateGasPriceDistribution(0.5);

      const lowSpread = Math.max(...lowVolatility) - Math.min(...lowVolatility);
      const highSpread = Math.max(...highVolatility) - Math.min(...highVolatility);

      expect(highSpread).toBeGreaterThan(lowSpread);
    });
  });

  describe('Estimate Result', () => {
    it('should produce consistent estimate results', () => {
      const result = gasBuilder
      .withChainId(1)
      .withBlockNumber(20000000)
      .withBaseFee(30_000_000_000)
      .buildEstimateResult();

      expect(result.chain_id).toBe(1);
      expect(result.block_number).toBe(20000000);
      expect(result.base_fee).toBe(30_000_000_000);
      expect(result.fast).toBeGreaterThan(result.standard);
      expect(result.standard).toBeGreaterThan(result.slow);
    });

    it('should have valid priority fees', () => {
      const result = gasBuilder
        .withBaseFee(30_000_000_000)
        .buildEstimateResult();

      expect(result.priority_fee_fast).toBeGreaterThan(0);
      expect(result.priority_fee_standard).toBeGreaterThan(0);
      expect(result.priority_fee_slow).toBeGreaterThan(0);
      expect(result.fast).toBe(result.base_fee + result.priority_fee_fast);
    });
  });

  describe('Historical Data', () => {
    it('should generate consistent history dataset with time progression', () => {
      const records = gasBuilder
        .withChainId(1)
        .withBlockNumber(20000000)
        .buildHistoryDataset();

      expect(records.length).toBe(24);

      const timestamps = records.map(r => new Date(r.block_time).getTime());

      for (let i = 1; i < timestamps.length; i++) {
        expect(timestamps[i]).toBeLessThan(timestamps[i - 1]);
      }

      records.forEach(record => {
        expect(record.chain_id).toBe(1);
        expect(record.high).toBeGreaterThan(record.average);
        expect(record.average).toBeGreaterThan(record.low);
        expect(record.average).toBeGreaterThan(record.base_fee);
      });
    });

    it('should generate blocks with transactions', () => {
      const builder = gasBuilder
        .withChainId(1)
        .withBlockNumber(20000000)
        .withBaseFee(30_000_000_000);

      builder.generateHistoryBlocks(50, 20);

      expect(builder.historyBlocks.length).toBe(50);

      builder.historyBlocks.forEach((block, index) => {
        expect(block.number).toBe(20000000 - index - 1);
        expect(block.transactions.length).toBe(20);

        block.transactions.forEach(tx => {
          expect(tx.gasPrice).toBeDefined();
          expect(tx.gasUsed).toBeGreaterThan(0);
        });
      });
    });
  });

  describe('Gas Price Records', () => {
    it('should build valid gas price records', () => {
      const record = gasBuilder
        .withChainId(137)
        .withBlockNumber(50000000)
        .buildGasPriceRecord({ low: 20_000_000_000 });

      expect(record.chain_id).toBe(137);
      expect(record.block_number).toBe(50000000);
      expect(record.low).toBe(20_000_000_000);
      expect(record.id).toBeDefined();
      expect(record.block_time).toBeDefined();
    });

    it('should apply overrides correctly', () => {
      const customTime = new Date('2025-01-01').toISOString();
      const record = gasBuilder.buildGasPriceRecord({
        block_time: customTime,
        base_fee: 50_000_000_000
      });

      expect(record.block_time).toBe(customTime);
      expect(record.base_fee).toBe(50_000_000_000);
    });
  });

  describe('Data Validation', () => {
    it('should ensure slow <= standard <= fast', () => {
      for (let i = 0; i < 100; i++) {
        const result = gasBuilder.buildEstimateResult();
        expect(result.slow).toBeLessThanOrEqual(result.standard);
        expect(result.standard).toBeLessThanOrEqual(result.fast);
      }
    });

    it('should generate valid block numbers', () => {
      const records = gasBuilder.buildHistoryDataset();
      const blockNumbers = records.map(r => r.block_number);

      const uniqueBlockNumbers = new Set(blockNumbers);
      expect(uniqueBlockNumbers.size).toBe(blockNumbers.length);

      for (let i = 1; i < blockNumbers.length; i++) {
        expect(blockNumbers[i]).toBeLessThan(blockNumbers[i - 1]);
      }
    });
  });
});
