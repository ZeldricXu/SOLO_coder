const { v4: uuidv4 } = require('uuid');
const { ethers } = require('ethers');

class ChainDataBuilder {
  constructor() {
    this.reset();
  }

  reset() {
    this.chainId = 1;
    this.chainName = 'ethereum';
    this.rpcUrl = 'https://mainnet.infura.io/v3/test';
    this.blockNumber = 20000000;
    this.blockTime = 15;
    return this;
  }

  withChainId(chainId) {
    this.chainId = chainId;
    return this;
  }

  withChainName(chainName) {
    this.chainName = chainName;
    return this;
  }

  withRPCUrl(rpcUrl) {
    this.rpcUrl = rpcUrl;
    return this;
  }

  withBlockNumber(blockNumber) {
    this.blockNumber = blockNumber;
    return this;
  }

  buildChainConfig(overrides = {}) {
    return {
      chain_id: this.chainId,
      name: this.chainName,
      rpc_url: this.rpcUrl,
      explorer: `https://${this.chainName}.etherscan.io`,
      ...overrides
    };
  }

  buildBlockHeader(overrides = {}) {
    const parentHash = '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 64);
    const hash = '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 64);

    return {
      number: this.blockNumber,
      hash: hash,
      parent_hash: parentHash,
      timestamp: Math.floor(Date.now() / 1000),
      gas_used: 15_000_000,
      gas_limit: 30_000_000,
      base_fee: 30_000_000_000,
      size: 100_000,
      ...overrides
    };
  }

  buildBlock(transactionCount = 10, overrides = {}) {
    const header = this.buildBlockHeader();
    const transactions = [];

    for (let i = 0; i < transactionCount; i++) {
      transactions.push(this.buildTransaction());
    }

    return {
      ...header,
      transactions: transactions.map(tx => tx.hash),
      transaction_details: transactions,
      ...overrides
    };
  }

  buildTransaction(overrides = {}) {
    const txHash = '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 64);
    const from = '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 40);
    const to = '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 40);

    return {
      hash: txHash,
      block_number: this.blockNumber,
      from: from,
      to: to,
      value: ethers.parseEther((Math.random() * 10).toString()).toString(),
      gas: 21000 + Math.floor(Math.random() * 100000),
      gas_price: 30_000_000_000 + Math.floor(Math.random() * 20_000_000_000),
      input: '0x',
      nonce: Math.floor(Math.random() * 100),
      status: 1,
      ...overrides
    };
  }

  buildTransactionReceipt(overrides = {}) {
    const txHash = '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 64);
    const contractAddress = Math.random() > 0.5
      ? '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 40)
      : null;

    return {
      transaction_hash: txHash,
      block_number: this.blockNumber,
      contract_address: contractAddress,
      gas_used: 21000 + Math.floor(Math.random() * 100000),
      cumulative_gas_used: 1_000_000,
      effective_gas_price: 30_000_000_000,
      status: 1,
      logs: [],
      ...overrides
    };
  }

  buildLog(overrides = {}) {
    const address = '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 40);
    const topics = [
      '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 64),
      '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 64)
    ];

    return {
      address: address,
      topics: topics,
      data: '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex'),
      block_number: this.blockNumber,
      transaction_hash: '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 64),
      index: Math.floor(Math.random() * 100),
      removed: false,
      ...overrides
    };
  }

  buildBalance(overrides = {}) {
    return {
      address: '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 40),
      balance: ethers.parseEther((Math.random() * 100).toString()).toString(),
      chain_id: this.chainId,
      ...overrides
    };
  }

  buildChainStatus(overrides = {}) {
    return {
      chain_id: this.chainId,
      latest_block: this.blockNumber,
      syncing: false,
      peers: 25,
      gas_price: 30_000_000_000,
      base_fee: 30_000_000_000,
      ...overrides
    };
  }

  buildBlockRange(startBlock, endBlock, txPerBlock = 10) {
    const blocks = [];
    for (let i = startBlock; i <= endBlock; i++) {
      this.blockNumber = i;
      blocks.push(this.buildBlock(txPerBlock));
    }
    return blocks;
  }

  buildTransactionBatch(count = 100) {
    const transactions = [];
    for (let i = 0; i < count; i++) {
      transactions.push(this.buildTransaction());
    }
    return transactions;
  }

  buildEventLogBatch(eventSignature, count = 50) {
    const logs = [];
    const eventTopic = ethers.keccak256(ethers.toUtf8Bytes(eventSignature));

    for (let i = 0; i < count; i++) {
      logs.push(this.buildLog({
        topics: [eventTopic],
        block_number: this.blockNumber - Math.floor(i / 10)
      }));
    }
    return logs;
  }
}

module.exports = ChainDataBuilder;
