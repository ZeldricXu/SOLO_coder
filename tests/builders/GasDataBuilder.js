const { v4: uuidv4 } = require('uuid');

class GasDataBuilder {
  constructor() {
    this.reset();
  }

  reset() {
    this.chainId = 1;
    this.blockNumber = 20000000;
    this.baseFee = 30_000_000_000;
    this.historyBlocks = [];
    this.percentile = 60;
    return this;
  }

  withChainId(chainId) {
    this.chainId = chainId;
    return this;
  }

  withBlockNumber(blockNumber) {
    this.blockNumber = blockNumber;
    return this;
  }

  withBaseFee(baseFee) {
    this.baseFee = baseFee;
    return this;
  }

  withPercentile(percentile) {
    this.percentile = percentile;
    return this;
  }

  addHistoryBlock(blockData = {}) {
    const defaultBlock = {
      number: this.blockNumber - this.historyBlocks.length - 1,
      timestamp: Date.now() / 1000 - (this.historyBlocks.length + 1) * 15,
      baseFeePerGas: this.baseFee + Math.floor(Math.random() * 10_000_000_000),
      transactions: []
    };

    const block = { ...defaultBlock, ...blockData };
    this.historyBlocks.push(block);
    return this;
  }

  addTransactionToLastBlock(txData = {}) {
    if (this.historyBlocks.length === 0) {
      this.addHistoryBlock();
    }

    const lastBlock = this.historyBlocks[this.historyBlocks.length - 1];
    const defaultTx = {
      gasPrice: this.baseFee + Math.floor(Math.random() * 20_000_000_000),
      maxFeePerGas: this.baseFee + Math.floor(Math.random() * 30_000_000_000),
      maxPriorityFeePerGas: 2_000_000_000 + Math.floor(Math.random() * 3_000_000_000),
      gasUsed: 21000 + Math.floor(Math.random() * 100000)
    };

    lastBlock.transactions.push({ ...defaultTx, ...txData });
    return this;
  }

  generateHistoryBlocks(count = 100, txPerBlock = 50) {
    for (let i = 0; i < count; i++) {
      this.addHistoryBlock();
      for (let j = 0; j < txPerBlock; j++) {
        this.addTransactionToLastBlock();
      }
    }
    return this;
  }

  generateGasPriceDistribution(volatility = 0.3) {
    const prices = [];
    const meanPrice = this.baseFee * 1.5;

    for (let i = 0; i < 1000; i++) {
      const variation = (Math.random() - 0.5) * 2 * volatility;
      const price = Math.floor(meanPrice * (1 + variation));
      prices.push(Math.max(1_000_000_000, price));
    }

    return prices.sort((a, b) => a - b);
  }

  buildEstimateResult() {
    const sortedPrices = this.generateGasPriceDistribution();
    const getPercentile = (p) => {
      const index = Math.floor(sortedPrices.length * p / 100);
      return sortedPrices[Math.min(index, sortedPrices.length - 1)];
    };

    return {
      chain_id: this.chainId,
      slow: getPercentile(this.percentile - 20),
      standard: getPercentile(this.percentile),
      fast: getPercentile(this.percentile + 20),
      base_fee: this.baseFee,
      priority_fee_slow: getPercentile(this.percentile - 20) - this.baseFee,
      priority_fee_standard: getPercentile(this.percentile) - this.baseFee,
      priority_fee_fast: getPercentile(this.percentile + 20) - this.baseFee,
      estimated_at: new Date().toISOString(),
      block_number: this.blockNumber
    };
  }

  buildGasPriceRecord(overrides = {}) {
    return {
      id: uuidv4(),
      chain_id: this.chainId,
      block_number: this.blockNumber,
      block_time: new Date().toISOString(),
      low: this.baseFee * 1.2,
      average: this.baseFee * 1.5,
      high: this.baseFee * 2,
      base_fee: this.baseFee,
      ...overrides
    };
  }

  buildHistoryDataset() {
    const records = [];
    const now = Date.now();

    for (let i = 0; i < 24; i++) {
      const hourBaseFee = this.baseFee + Math.sin(i / 4) * 10_000_000_000;
      records.push({
        id: uuidv4(),
        chain_id: this.chainId,
        block_number: this.blockNumber - i * 300,
        block_time: new Date(now - i * 3600000).toISOString(),
        low: Math.floor(hourBaseFee * 1.1),
        average: Math.floor(hourBaseFee * 1.5),
        high: Math.floor(hourBaseFee * 2),
        base_fee: Math.floor(hourBaseFee)
      });
    }

    return records;
  }
}

module.exports = GasDataBuilder;
