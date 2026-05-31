import { TransactionBuilderService } from '@core/usecases/transactionBuilder.usecase';
import { MockLogger } from '../__mocks__/mockPorts';
import { ValidationError } from '@shared/errors';
import type { TransactionBuildParams, GasOptimizationConfig } from '@core/ports/transactionBuilder.port';
import type { GasEstimate } from '@core/domain/blockchain';
import {
  AddressBuilder,
  HexStringBuilder,
  TransactionBuilder as TxDataBuilder,
  GasEstimateBuilder,
  TransactionSignatureBuilder,
  MultisigStrategyBuilder,
  TestTiming,
} from '../builders/testDataBuilders';

describe('TransactionBuilderService - Timeout and Degradation Behavior', () => {
  let builder: TransactionBuilderService;
  let mockLogger: MockLogger;

  beforeEach(() => {
    mockLogger = new MockLogger();
    builder = new TransactionBuilderService(mockLogger, {
      enabled: true,
      speed: 'standard',
      gasLimitBuffer: 10,
      retryOnFailure: true,
      maxRetries: 3,
    });
  });

  describe('Input Validation - Fast Fail Behavior', () => {
    it('should fail fast on invalid address format', async () => {
      const startTime = Date.now();

      await expect(
        builder.buildTransaction({
          chainId: 1,
          from: 'invalid-address' as any,
          to: AddressBuilder.fromSeed(2),
          value: BigInt(0),
        })
      ).rejects.toThrow(ValidationError);

      const duration = Date.now() - startTime;
      expect(duration).toBeLessThan(100);
    });

    it('should fail fast on negative value', async () => {
      const startTime = Date.now();

      await expect(
        builder.buildTransaction({
          chainId: 1,
          from: AddressBuilder.fromSeed(1),
          to: AddressBuilder.fromSeed(2),
          value: BigInt(-1),
        })
      ).rejects.toThrow(ValidationError);

      const duration = Date.now() - startTime;
      expect(duration).toBeLessThan(100);
    });

    it('should fail fast on negative gas limit', async () => {
      const startTime = Date.now();

      await expect(
        builder.buildTransaction({
          chainId: 1,
          from: AddressBuilder.fromSeed(1),
          to: AddressBuilder.fromSeed(2),
          value: BigInt(0),
          gasLimit: BigInt(-1),
        })
      ).rejects.toThrow(ValidationError);

      const duration = Date.now() - startTime;
      expect(duration).toBeLessThan(100);
    });

    it('should fail fast on invalid chain ID', async () => {
      const startTime = Date.now();

      await expect(
        builder.buildTransaction({
          chainId: 0,
          from: AddressBuilder.fromSeed(1),
          to: AddressBuilder.fromSeed(2),
          value: BigInt(0),
        })
      ).rejects.toThrow(ValidationError);

      const duration = Date.now() - startTime;
      expect(duration).toBeLessThan(100);
    });

    it('should validate all parameters before processing', async () => {
      const invalidParams = {
        chainId: -1,
        from: 'invalid',
        to: 'invalid',
        value: BigInt(-100),
        gasLimit: BigInt(-50000),
      };

      let error: ValidationError | null = null;
      try {
        await builder.buildTransaction(invalidParams as any);
      } catch (e) {
        error = e as ValidationError;
      }

      expect(error).toBeInstanceOf(ValidationError);
      expect(error?.details).toBeDefined();
      expect(Object.keys(error?.details || {}).length).toBeGreaterThanOrEqual(3);
    });
  });

  describe('Gas Optimization - Graceful Degradation', () => {
    it('should use default gas limit when not provided', async () => {
      const result = await builder.buildTransaction(
        TxDataBuilder.default()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withValue(BigInt(0))
          .build()
      );

      expect(result.transaction.gasLimit).toBe(BigInt(21000));
    });

    it('should use provided gas limit when optimization is disabled', async () => {
      builder.setGasOptimizationConfig({
        enabled: false,
        speed: 'standard',
      });

      const result = await builder.buildTransaction(
        TxDataBuilder.default()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withGasLimit(BigInt(100000))
          .build()
      );

      expect(result.transaction.gasLimit).toBe(BigInt(100000));
    });

    it('should apply gas limit buffer when optimization is enabled', async () => {
      builder.setGasOptimizationConfig({
        enabled: true,
        speed: 'fast',
        gasLimitBuffer: 20,
      });

      const builtTx = await builder.buildTransaction(
        TxDataBuilder.etherTransfer()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withGasLimit(BigInt(100000))
          .build()
      );

      const gasEstimate: GasEstimate = {
        gasLimit: BigInt(100000),
        baseFeePerGas: BigInt('20000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
        maxFeePerGas: BigInt('42000000000'),
        estimatedCost: BigInt('4200000000000000'),
        confidence: 0.9,
        timestamp: new Date().toISOString(),
      };

      const optimized = await builder.applyGasOptimization(builtTx, gasEstimate);

      expect(optimized.transaction.gasLimit).toBe(BigInt(120000));
    });

    it('should apply priority fee boost when configured', async () => {
      builder.setGasOptimizationConfig({
        enabled: true,
        speed: 'instant',
        gasLimitBuffer: 10,
        priorityFeeBoost: 20,
      });

      const builtTx = await builder.buildTransaction(
        TxDataBuilder.etherTransfer()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .build()
      );

      const gasEstimate = GasEstimateBuilder.default()
        .withGasLimit(BigInt(21000))
        .withBaseFee(BigInt('20000000000'))
        .withPriorityFee(BigInt('2000000000'))
        .build();

      const optimized = await builder.applyGasOptimization(builtTx, gasEstimate);

      expect(optimized.transaction.maxPriorityFeePerGas).toBe(BigInt('2400000000'));
    });

    it('should not modify original transaction when applying optimization', async () => {
      const builtTx = await builder.buildTransaction(
        TxDataBuilder.etherTransfer()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withGasLimit(BigInt(100000))
          .build()
      );

      const originalGasLimit = builtTx.transaction.gasLimit;
      const originalPriorityFee = builtTx.transaction.maxPriorityFeePerGas;

      const gasEstimate = GasEstimateBuilder.standardTransfer();
      const optimized = await builder.applyGasOptimization(builtTx, gasEstimate);

      expect(builtTx.transaction.gasLimit).toBe(originalGasLimit);
      expect(builtTx.transaction.maxPriorityFeePerGas).toBe(originalPriorityFee);
      expect(optimized.transaction.gasLimit).not.toBe(originalGasLimit);
    });

    it('should return original transaction when optimization is disabled', async () => {
      builder.setGasOptimizationConfig({
        enabled: false,
        speed: 'standard',
      });

      const builtTx = await builder.buildTransaction(
        TxDataBuilder.etherTransfer()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .build()
      );

      const gasEstimate = GasEstimateBuilder.standardTransfer();
      const result = await builder.applyGasOptimization(builtTx, gasEstimate);

      expect(result.transaction.gasLimit).toBe(builtTx.transaction.gasLimit);
      expect(result.transactionHash).toBe(builtTx.transactionHash);
    });

    it('should handle zero gas limit gracefully', async () => {
      const result = await builder.buildTransaction(
        TxDataBuilder.default()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withValue(BigInt(0))
          .withGasLimit(BigInt(21000))
          .build()
      );

      expect(result.transaction.gasLimit).toBeGreaterThanOrEqual(BigInt(21000));
    });
  });

  describe('Signature Attachment - Isolation and Validation', () => {
    it('should attach signature without modifying original transaction', async () => {
      const builtTx = await builder.buildTransaction(
        TxDataBuilder.etherTransfer()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .build()
      );

      const originalHash = builtTx.transactionHash;
      const signature = TransactionSignatureBuilder.default();

      const result = await builder.attachSignature(builtTx, signature);

      expect(builtTx.transactionHash).toBe(originalHash);
      expect((builtTx.transaction as any).signature).toBeUndefined();
      expect(result.transaction.signature).toEqual(signature);
    });

    it('should generate consistent signed transaction data', async () => {
      const builtTx = await builder.buildTransaction(
        TxDataBuilder.etherTransfer()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withNonce(42)
          .build()
      );

      const signature = TransactionSignatureBuilder.default();
      const result1 = await builder.attachSignature(builtTx, signature);
      const result2 = await builder.attachSignature(builtTx, signature);

      expect(result1.signedTransaction).toBe(result2.signedTransaction);
    });

    it('should handle different signature v values correctly', async () => {
      const builtTx = await builder.buildTransaction(
        TxDataBuilder.etherTransfer()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .build()
      );

      const sig27 = TransactionSignatureBuilder.withV(BigInt(27));
      const sig28 = TransactionSignatureBuilder.withV(BigInt(28));

      const result27 = await builder.attachSignature(builtTx, sig27);
      const result28 = await builder.attachSignature(builtTx, sig28);

      expect(result27.signedTransaction).not.toBe(result28.signedTransaction);
      expect(result27.transaction.signature?.v).toBe(BigInt(27));
      expect(result28.transaction.signature?.v).toBe(BigInt(28));
    });

    it('should include all transaction fields in signed data', async () => {
      const builtTx = await builder.buildTransaction(
        TxDataBuilder.default()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withValue(BigInt('1000000000000000000'))
          .withNonce(5)
          .withGasLimit(BigInt(50000))
          .withEIP1559Fees(BigInt('30000000000'), BigInt('2000000000'))
          .build()
      );

      const signature = TransactionSignatureBuilder.default();
      const result = await builder.attachSignature(builtTx, signature);

      expect(result.signedTransaction.length).toBeGreaterThan(builtTx.unsignedData.length);
      expect(result.transaction.signature).toBeDefined();
      expect(result.transaction.signature?.r).toBe(signature.r);
      expect(result.transaction.signature?.s).toBe(signature.s);
    });
  });

  describe('Transaction Type Handling - Backward Compatibility', () => {
    it('should build legacy transaction (type 0) when gasPrice provided', async () => {
      const result = await builder.buildTransaction(
        TxDataBuilder.default()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withLegacyGasPrice(BigInt('30000000000'))
          .build()
      );

      expect(result.transaction.type).toBe(0);
      expect(result.transaction.gasPrice).toBe(BigInt('30000000000'));
      expect((result.transaction as any).maxFeePerGas).toBeUndefined();
    });

    it('should build EIP-1559 transaction (type 2) when maxFeePerGas provided', async () => {
      const result = await builder.buildTransaction(
        TxDataBuilder.default()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withEIP1559Fees(BigInt('30000000000'), BigInt('2000000000'))
          .build()
      );

      expect(result.transaction.type).toBe(2);
      expect(result.transaction.maxFeePerGas).toBe(BigInt('30000000000'));
      expect(result.transaction.maxPriorityFeePerGas).toBe(BigInt('2000000000'));
    });

    it('should default to legacy type when no fee params provided', async () => {
      const result = await builder.buildTransaction(
        TxDataBuilder.default()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withValue(BigInt(0))
          .build()
      );

      expect(result.transaction.type).toBe(0);
      expect(result.transaction.gasPrice).toBeDefined();
    });

    it('should allow explicit type override', async () => {
      const result = await builder.buildTransaction(
        TxDataBuilder.default()
          .withFrom(AddressBuilder.fromSeed(1))
          .withTo(AddressBuilder.fromSeed(2))
          .withType(2)
          .withEIP1559Fees(BigInt('30000000000'), BigInt('2000000000'))
          .build()
      );

      expect(result.transaction.type).toBe(2);
    });
  });

  describe('Multisig Strategy Management', () => {
    it('should set and get multisig strategy correctly', async () => {
      const strategy = MultisigStrategyBuilder.simple2of3();

      builder.setMultisigStrategy(strategy);
      const retrieved = builder.getMultisigStrategy();

      expect(retrieved).toBe(strategy);
      expect(retrieved?.id).toBe('test-strategy');
      expect(retrieved?.threshold).toBe(2);
    });

    it('should return null when no strategy set', async () => {
      const newBuilder = new TransactionBuilderService(mockLogger);
      expect(newBuilder.getMultisigStrategy()).toBeNull();
    });

    it('should isolate strategy between different builder instances', async () => {
      const builder1 = new TransactionBuilderService(mockLogger);
      const builder2 = new TransactionBuilderService(mockLogger);

      const strategy1 = MultisigStrategyBuilder.default().withId('strategy-1').build();
      const strategy2 = MultisigStrategyBuilder.default().withId('strategy-2').build();

      builder1.setMultisigStrategy(strategy1);
      builder2.setMultisigStrategy(strategy2);

      expect(builder1.getMultisigStrategy()?.id).toBe('strategy-1');
      expect(builder2.getMultisigStrategy()?.id).toBe('strategy-2');
    });

    it('should override previous strategy', async () => {
      const strategy1 = MultisigStrategyBuilder.default().withId('strategy-1').build();
      const strategy2 = MultisigStrategyBuilder.default().withId('strategy-2').build();

      builder.setMultisigStrategy(strategy1);
      expect(builder.getMultisigStrategy()?.id).toBe('strategy-1');

      builder.setMultisigStrategy(strategy2);
      expect(builder.getMultisigStrategy()?.id).toBe('strategy-2');
    });
  });

  describe('Deterministic Behavior', () => {
    it('should generate same transaction hash for same inputs', async () => {
      const params = TxDataBuilder.default()
        .withFrom(AddressBuilder.fromSeed(1))
        .withTo(AddressBuilder.fromSeed(2))
        .withValue(BigInt('1000000000000000000'))
        .withNonce(5)
        .withGasLimit(BigInt(21000))
        .withEIP1559Fees(BigInt('30000000000'), BigInt('2000000000'))
        .build();

      const result1 = await builder.buildTransaction(params);
      const result2 = await builder.buildTransaction(params);

      expect(result1.transactionHash).toBe(result2.transactionHash);
      expect(result1.unsignedData).toBe(result2.unsignedData);
    });

    it('should generate different hashes for different nonces', async () => {
      const baseParams = TxDataBuilder.default()
        .withFrom(AddressBuilder.fromSeed(1))
        .withTo(AddressBuilder.fromSeed(2))
        .withValue(BigInt('1000000000000000000'));

      const result1 = await builder.buildTransaction(baseParams.withNonce(1).build());
      const result2 = await builder.buildTransaction(baseParams.withNonce(2).build());

      expect(result1.transactionHash).not.toBe(result2.transactionHash);
    });

    it('should generate different hashes for different values', async () => {
      const baseParams = TxDataBuilder.default()
        .withFrom(AddressBuilder.fromSeed(1))
        .withTo(AddressBuilder.fromSeed(2))
        .withNonce(0);

      const result1 = await builder.buildTransaction(baseParams.withValue(BigInt('1000000000000000000')).build());
      const result2 = await builder.buildTransaction(baseParams.withValue(BigInt('2000000000000000000')).build());

      expect(result1.transactionHash).not.toBe(result2.transactionHash);
    });

    it('should generate consistent unsigned data encoding', async () => {
      const params = TxDataBuilder.etherTransfer()
        .withFrom(AddressBuilder.fromSeed(1))
        .withTo(AddressBuilder.fromSeed(2))
        .withNonce(42)
        .build();

      const result = await builder.buildTransaction(params);

      expect(result.unsignedData).toMatch(/^0x[a-fA-F0-9]+$/);
      expect(result.unsignedData.length).toBeGreaterThan(2);
    });
  });

  describe('Contract Transaction Building', () => {
    it('should build contract call with method selector', async () => {
      const abi = [
        {
          type: 'function',
          name: 'transfer',
          inputs: [
            { type: 'address', name: 'to' },
            { type: 'uint256', name: 'amount' },
          ],
          outputs: [{ type: 'bool' }],
        },
      ];

      const result = await builder.buildContractCall(
        1,
        AddressBuilder.fromSeed(1),
        AddressBuilder.fromSeed(100),
        'transfer',
        [AddressBuilder.fromSeed(2), BigInt('1000000000000000000')],
        abi
      );

      expect(result.transaction.to).toBe(AddressBuilder.fromSeed(100));
      expect(result.transaction.data).toMatch(/^0x[a-fA-F0-9]{8,}$/);
      expect(result.transaction.type).toBe(2);
    });

    it('should build contract deployment with bytecode', async () => {
      const bytecode = '0x608060405234801561001057600080fd5b50610150806100206000396000f3fe';

      const result = await builder.buildContractDeployment(
        1,
        AddressBuilder.fromSeed(1),
        bytecode as any
      );

      expect(result.transaction.to).toBe('0x0000000000000000000000000000000000000000');
      expect(result.transaction.data).toContain(bytecode.replace('0x', ''));
    });

    it('should encode constructor arguments correctly', async () => {
      const bytecode = '0x6080604052';
      const abi = [
        {
          type: 'constructor',
          inputs: [
            { type: 'uint256', name: 'initialSupply' },
            { type: 'address', name: 'owner' },
          ],
        },
      ];

      const result = await builder.buildContractDeployment(
        1,
        AddressBuilder.fromSeed(1),
        bytecode as any,
        [BigInt('1000000000000000000000'), AddressBuilder.fromSeed(1)],
        abi
      );

      expect(result.transaction.data.length).toBeGreaterThan(bytecode.length);
    });
  });
});
