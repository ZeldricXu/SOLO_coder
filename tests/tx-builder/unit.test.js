const { ethers } = require('ethers');
const { TransactionBuilder } = require('../builders');

describe('Transaction Builder Module - Concurrency & Isolation', () => {
  let txBuilder;

  beforeEach(() => {
    txBuilder = new TransactionBuilder();
  });

  describe('Builder State Isolation', () => {
    it('should maintain independent state for each builder instance', () => {
      const builder1 = new TransactionBuilder().withChainId(1).withGasPrice(30_000_000_000);
      const builder2 = new TransactionBuilder().withChainId(137).withGasPrice(50_000_000_000);

      const tx1 = builder1.buildLegacyTransactionRequest();
      const tx2 = builder2.buildLegacyTransactionRequest();

      expect(tx1.chain_id).toBe(1);
      expect(tx1.gas_price).toBe(30_000_000_000);
      expect(tx2.chain_id).toBe(137);
      expect(tx2.gas_price).toBe(50_000_000_000);
    });

    it('should not interfere between consecutive build calls', () => {
      const tx1 = txBuilder.withChainId(1).buildLegacyTransactionRequest();
      const tx2 = txBuilder.withChainId(137).buildLegacyTransactionRequest();
      const tx3 = txBuilder.reset().withChainId(42161).buildLegacyTransactionRequest();

      expect(tx1.chain_id).toBe(1);
      expect(tx2.chain_id).toBe(137);
      expect(tx3.chain_id).toBe(42161);
    });

    it('should create independent copies with reset', () => {
      txBuilder.withChainId(1).withValue(ethers.parseEther('1.0'));
      const tx1 = txBuilder.buildLegacyTransactionRequest();

      txBuilder.reset();
      txBuilder.withChainId(137);
      const tx2 = txBuilder.buildLegacyTransactionRequest();

      expect(tx1.value).not.toBe(tx2.value);
      expect(tx1.chain_id).toBe(1);
      expect(tx2.chain_id).toBe(137);
    });
  });

  describe('Transaction Type Consistency', () => {
    it('should build valid legacy transactions', () => {
      const tx = txBuilder
        .withChainId(1)
        .withFrom('0x' + '1'.repeat(40))
        .withTo('0x' + '2'.repeat(40))
        .withValue(ethers.parseEther('0.5'))
        .withGasPrice(30_000_000_000)
        .withGasLimit(21000)
        .buildLegacyTransactionRequest();

      expect(tx.chain_id).toBe(1);
      expect(tx.gas_price).toBe(30_000_000_000);
      expect(tx.max_fee_per_gas).toBeUndefined();
      expect(tx.max_priority_fee_per_gas).toBeUndefined();
      expect(typeof tx.value).toBe('string');
    });

    it('should build valid EIP-1559 transactions', () => {
      const tx = txBuilder
        .withChainId(1)
        .withMaxFeePerGas(50_000_000_000)
        .withMaxPriorityFeePerGas(2_000_000_000)
        .buildEIP1559TransactionRequest();

      expect(tx.max_fee_per_gas).toBe(50_000_000_000);
      expect(tx.max_priority_fee_per_gas).toBe(2_000_000_000);
      expect(tx.gas_price).toBeUndefined();
    });

    it('should build valid multi-sig transactions', () => {
      const signers = txBuilder.generateMultiSigSigners(5);
      const safeAddress = '0x' + '3'.repeat(40);

      const tx = txBuilder
        .withMultiSig(3, signers, safeAddress)
        .buildMultiSigTransactionRequest();

      expect(tx.multi_sig_config).toBeDefined();
      expect(tx.multi_sig_config.threshold).toBe(3);
      expect(tx.multi_sig_config.signers.length).toBe(5);
      expect(tx.multi_sig_config.safe_address).toBe(safeAddress);
    });
  });

  describe('Signer Management', () => {
    it('should generate valid random signers', () => {
      const wallet = txBuilder.generateRandomSigner();

      expect(wallet.address).toBeDefined();
      expect(wallet.privateKey).toBeDefined();
      expect(wallet.address).toMatch(/^0x[a-fA-F0-9]{40}$/);
    });

    it('should generate multiple independent signers', () => {
      const signers = [];
      for (let i = 0; i < 10; i++) {
        signers.push(txBuilder.generateRandomSigner());
      }

      const addresses = new Set(signers.map(s => s.address));
      expect(addresses.size).toBe(10);

      const privateKeys = new Set(signers.map(s => s.privateKey));
      expect(privateKeys.size).toBe(10);
    });

    it('should generate deterministic multi-sig signer sets', () => {
      const signers1 = txBuilder.generateMultiSigSigners(5);
      const signers2 = txBuilder.generateMultiSigSigners(5);

      expect(signers1.length).toBe(5);
      expect(signers2.length).toBe(5);

      const allSigners = new Set([...signers1, ...signers2]);
      expect(allSigners.size).toBe(10);
    });
  });

  describe('Concurrency Safety', () => {
    it('should handle concurrent build operations safely', async () => {
      const concurrency = 50;
      const promises = [];

      for (let i = 0; i < concurrency; i++) {
        promises.push(
          new Promise((resolve) => {
            const builder = new TransactionBuilder();
            const tx = builder
              .withChainId(i % 3 === 0 ? 1 : i % 3 === 1 ? 137 : 42161)
              .withNonce(i)
              .buildLegacyTransactionRequest();
            resolve(tx);
          })
        );
      }

      const results = await Promise.all(promises);

      expect(results.length).toBe(concurrency);

      const nonces = results.map(r => r.nonce).sort((a, b) => a - b);
      for (let i = 0; i < concurrency; i++) {
        expect(nonces[i]).toBe(i);
      }
    });

    it('should maintain nonce isolation across builds', () => {
      const tx1 = txBuilder.withNonce(0).buildLegacyTransactionRequest();
      const tx2 = txBuilder.withNonce(1).buildLegacyTransactionRequest();
      const tx3 = txBuilder.withNonce(2).buildLegacyTransactionRequest();

      expect(tx1.nonce).toBe(0);
      expect(tx2.nonce).toBe(1);
      expect(tx3.nonce).toBe(2);
    });

    it('should handle concurrent signature generation', async () => {
      const concurrency = 20;
      const privateKeys = [];

      for (let i = 0; i < concurrency; i++) {
        const wallet = ethers.Wallet.createRandom();
        privateKeys.push(wallet.privateKey);
      }

      const promises = privateKeys.map(async (key) => {
        const builder = new TransactionBuilder();
        return builder.buildRealSignedTransaction(key);
      });

      const results = await Promise.all(promises);

      expect(results.length).toBe(concurrency);
      results.forEach(result => {
        expect(result.tx_hash).toBeDefined();
        expect(result.raw_tx).toBeDefined();
        expect(result.signatures.length).toBe(1);
      });

      const txHashes = new Set(results.map(r => r.tx_hash));
      expect(txHashes.size).toBe(concurrency);
    });
  });

  describe('Multi-Sig Isolation', () => {
    it('should collect signatures independently', () => {
      const signers = txBuilder.generateMultiSigSigners(5);
      const safeAddress = '0x' + 'a'.repeat(40);

      const tx1 = txBuilder
        .withMultiSig(3, signers.slice(0, 3), safeAddress)
        .buildMultiSigTransactionRequest();

      const tx2 = txBuilder
        .withMultiSig(2, signers.slice(2, 4), safeAddress)
        .buildMultiSigTransactionRequest();

      expect(tx1.multi_sig_config.threshold).toBe(3);
      expect(tx1.multi_sig_config.signers.length).toBe(3);
      expect(tx2.multi_sig_config.threshold).toBe(2);
      expect(tx2.multi_sig_config.signers.length).toBe(2);
    });

    it('should build signed transactions with multiple signatures', () => {
      const singleSig = txBuilder.buildSignedTransactionWithSignatures(1);
      const multiSig = txBuilder.buildSignedTransactionWithSignatures(3);

      expect(singleSig.signatures.length).toBe(1);
      expect(multiSig.signatures.length).toBe(3);

      singleSig.signatures.forEach(sig => {
        expect(sig).toMatch(/^0x[a-fA-F0-9]+$/);
      });
    });
  });

  describe('Data Encoding Consistency', () => {
    it('should encode ERC20 transfer data consistently', () => {
      const to = '0x' + 'b'.repeat(40);
      const amount = ethers.parseEther('100');

      const data1 = txBuilder.buildERC20TransferData(to, amount);
      const data2 = txBuilder.buildERC20TransferData(to, amount);

      expect(data1).toBe(data2);
      expect(data1).toMatch(/^0x[a-fA-F0-9]+$/);
    });

    it('should encode different contract calls differently', () => {
      const to = '0x' + 'c'.repeat(40);
      const amount = ethers.parseEther('100');

      const erc20Data = txBuilder.buildERC20TransferData(to, amount);
      const erc721Data = txBuilder.buildERC721TransferData(
        '0x' + 'd'.repeat(40),
        to,
        1
      );

      expect(erc20Data).not.toBe(erc721Data);
      expect(erc20Data.slice(0, 10)).not.toBe(erc721Data.slice(0, 10));
    });
  });

  describe('Gas Optimization', () => {
    it('should apply gas optimization when enabled', () => {
      const txWithoutOpt = txBuilder
        .withGasOptimization(false)
        .withGasPrice(30_000_000_000)
        .buildLegacyTransactionRequest();

      const txWithOpt = txBuilder
        .withGasOptimization(true)
        .withGasPrice(30_000_000_000)
        .buildLegacyTransactionRequest();

      expect(txWithoutOpt.gas_optimization).toBe(false);
      expect(txWithOpt.gas_optimization).toBe(true);
    });
  });
});
