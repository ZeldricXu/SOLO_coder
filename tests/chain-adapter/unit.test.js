const { ChainDataBuilder, MockServerBuilder } = require('../builders');

describe('Chain Adapter Module - Timeout & Fallback Behavior', () => {
  let chainBuilder;

  beforeEach(() => {
    chainBuilder = new ChainDataBuilder();
  });

  describe('Chain Configuration', () => {
    it('should build valid chain configurations', () => {
      const config = chainBuilder
        .withChainId(1)
        .withChainName('ethereum')
        .withRPCUrl('https://mainnet.infura.io/v3/test')
        .buildChainConfig();

      expect(config.chain_id).toBe(1);
      expect(config.name).toBe('ethereum');
      expect(config.rpc_url).toBe('https://mainnet.infura.io/v3/test');
      expect(config.explorer).toBeDefined();
    });

    it('should support multiple chain configurations', () => {
      const chains = [
        { id: 1, name: 'ethereum' },
        { id: 137, name: 'polygon' },
        { id: 42161, name: 'arbitrum' }
      ];

      const configs = chains.map(chain =>
        chainBuilder
          .reset()
          .withChainId(chain.id)
          .withChainName(chain.name)
          .buildChainConfig()
      );

      expect(configs.length).toBe(3);
      const uniqueIds = new Set(configs.map(c => c.chain_id));
      expect(uniqueIds.size).toBe(3);
    });
  });

  describe('Block Data Construction', () => {
    it('should build consistent block headers', () => {
      const header1 = chainBuilder
        .withBlockNumber(20000000)
        .buildBlockHeader();

      const header2 = chainBuilder
        .withBlockNumber(20000000)
        .buildBlockHeader();

      expect(header1.number).toBe(20000000);
      expect(header1.gas_limit).toBe(30_000_000);
      expect(header1.base_fee).toBe(30_000_000_000);

      expect(header1.hash).not.toBe(header2.hash);
      expect(header1.parent_hash).not.toBe(header2.parent_hash);
    });

    it('should build blocks with transactions', () => {
      const txCount = 100;
      const block = chainBuilder
        .withBlockNumber(20000000)
        .buildBlock(txCount);

      expect(block.transactions.length).toBe(txCount);
      expect(block.transaction_details.length).toBe(txCount);
      expect(block.number).toBe(20000000);
    });

    it('should build consistent block ranges', () => {
      const startBlock = 20000000;
      const endBlock = 20000099;
      const blocks = chainBuilder.buildBlockRange(startBlock, endBlock, 10);

      expect(blocks.length).toBe(100);

      for (let i = 0; i < blocks.length; i++) {
        expect(blocks[i].number).toBe(startBlock + i);
        if (i > 0) {
          expect(blocks[i].parent_hash).not.toBe(blocks[i - 1].hash);
        }
      }
    });
  });

  describe('Transaction Data Construction', () => {
    it('should build valid transactions', () => {
      const tx = chainBuilder.buildTransaction();

      expect(tx.hash).toMatch(/^0x[a-fA-F0-9]{64}$/);
      expect(tx.from).toMatch(/^0x[a-fA-F0-9]{40}$/);
      expect(tx.to).toMatch(/^0x[a-fA-F0-9]{40}$/);
      expect(tx.gas).toBeGreaterThanOrEqual(21000);
      expect(tx.gas_price).toBeGreaterThan(0);
      expect(typeof tx.value).toBe('string');
    });

    it('should generate unique transactions', () => {
      const txCount = 100;
      const txs = chainBuilder.buildTransactionBatch(txCount);

      expect(txs.length).toBe(txCount);

      const hashes = new Set(txs.map(tx => tx.hash));
      expect(hashes.size).toBe(txCount);
    });

    it('should build transaction receipts', () => {
      const receipt = chainBuilder.buildTransactionReceipt();

      expect(receipt.transaction_hash).toBeDefined();
      expect(receipt.block_number).toBeDefined();
      expect(receipt.gas_used).toBeGreaterThan(0);
      expect(receipt.status).toBe(1);
      expect(Array.isArray(receipt.logs)).toBe(true);
    });
  });

  describe('Event Log Construction', () => {
    it('should build valid event logs', () => {
      const log = chainBuilder.buildLog();

      expect(log.address).toMatch(/^0x[a-fA-F0-9]{40}$/);
      expect(Array.isArray(log.topics)).toBe(true);
      expect(log.topics.length).toBeGreaterThan(0);
      expect(log.data).toMatch(/^0x[a-fA-F0-9]+$/);
      expect(log.block_number).toBeGreaterThan(0);
      expect(log.removed).toBe(false);
    });

    it('should build event log batches with same signature', () => {
      const eventSignature = 'Transfer(address,address,uint256)';
      const logs = chainBuilder.buildEventLogBatch(eventSignature, 50);

      expect(logs.length).toBe(50);

      const eventTopic = logs[0].topics[0];
      logs.forEach(log => {
        expect(log.topics[0]).toBe(eventTopic);
      });
    });
  });

  describe('Balance & Status Data', () => {
    it('should build balance data', () => {
      const balance = chainBuilder.buildBalance();

      expect(balance.address).toMatch(/^0x[a-fA-F0-9]{40}$/);
      expect(typeof balance.balance).toBe('string');
      expect(balance.chain_id).toBe(1);
    });

    it('should build chain status data', () => {
      const status = chainBuilder
        .withChainId(137)
        .withBlockNumber(50000000)
        .buildChainStatus();

      expect(status.chain_id).toBe(137);
      expect(status.latest_block).toBe(50000000);
      expect(status.syncing).toBe(false);
      expect(status.gas_price).toBe(30_000_000_000);
    });
  });

  describe('Data Validation', () => {
    it('should ensure block numbers are sequential in ranges', () => {
      const blocks = chainBuilder.buildBlockRange(100, 199);

      for (let i = 1; i < blocks.length; i++) {
        expect(blocks[i].number).toBe(blocks[i - 1].number + 1);
      }
    });

    it('should generate unique addresses for transactions', () => {
      const txs = chainBuilder.buildTransactionBatch(100);

      const fromAddresses = new Set(txs.map(tx => tx.from));
      const toAddresses = new Set(txs.map(tx => tx.to));

      expect(fromAddresses.size).toBeGreaterThan(1);
      expect(toAddresses.size).toBeGreaterThan(1);
    });
  });

  describe('Mock Server Builder', () => {
    it('should create mock server instances', () => {
      const mockServer = new MockServerBuilder('http://test:8080');
      expect(mockServer).toBeDefined();
    });

    it('should reset mock server state', () => {
      const mockServer = new MockServerBuilder('http://test:8080');

      mockServer.mockHealthCheck();
      expect(mockServer.scopes.length).toBe(1);

      mockServer.reset();
      expect(mockServer.scopes.length).toBe(0);
    });
  });
});
