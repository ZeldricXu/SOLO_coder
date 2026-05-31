import { TransactionBuilderService } from '@core/usecases/transactionBuilder.usecase';
import { MockLogger } from '../__mocks__/mockPorts';
import { ValidationError } from '@shared/errors';
import type { MultisigStrategy } from '@core/ports/transactionBuilder.port';

describe('TransactionBuilderService', () => {
  let builder: TransactionBuilderService;
  let mockLogger: MockLogger;

  beforeEach(() => {
    mockLogger = new MockLogger();
    builder = new TransactionBuilderService(mockLogger);
  });

  describe('buildTransaction', () => {
    it('should build a valid transaction', async () => {
      const result = await builder.buildTransaction({
        chainId: 1,
        from: '0x0000000000000000000000000000000000000001',
        to: '0x0000000000000000000000000000000000000002',
        value: BigInt('1000000000000000000'),
        nonce: 0,
        gasLimit: BigInt(21000),
        maxFeePerGas: BigInt('30000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
      });

      expect(result).toBeDefined();
      expect(result.transactionHash).toBeDefined();
      expect(result.unsignedData).toBeDefined();
      expect(result.transaction.from).toBe('0x0000000000000000000000000000000000000001');
      expect(result.transaction.to).toBe('0x0000000000000000000000000000000000000002');
      expect(result.transaction.value).toBe(BigInt('1000000000000000000'));
      expect(result.transaction.type).toBe(2);
    });

    it('should build legacy transaction when gasPrice is provided', async () => {
      const result = await builder.buildTransaction({
        chainId: 1,
        from: '0x0000000000000000000000000000000000000001',
        to: '0x0000000000000000000000000000000000000002',
        value: BigInt(0),
        nonce: 0,
        gasLimit: BigInt(21000),
        gasPrice: BigInt('30000000000'),
      });

      expect(result.transaction.type).toBe(0);
      expect(result.transaction.gasPrice).toBe(BigInt('30000000000'));
    });

    it('should throw validation error for invalid address', async () => {
      await expect(
        builder.buildTransaction({
          chainId: 1,
          from: 'invalid-address',
          to: '0x0000000000000000000000000000000000000002',
          value: BigInt(0),
        })
      ).rejects.toThrow(ValidationError);
    });

    it('should throw validation error for negative value', async () => {
      await expect(
        builder.buildTransaction({
          chainId: 1,
          from: '0x0000000000000000000000000000000000000001',
          to: '0x0000000000000000000000000000000000000002',
          value: BigInt(-1),
        })
      ).rejects.toThrow(ValidationError);
    });
  });

  describe('attachSignature', () => {
    it('should attach signature to transaction', async () => {
      const builtTx = await builder.buildTransaction({
        chainId: 1,
        from: '0x0000000000000000000000000000000000000001',
        to: '0x0000000000000000000000000000000000000002',
        value: BigInt(0),
        nonce: 0,
        gasLimit: BigInt(21000),
        maxFeePerGas: BigInt('30000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
      });

      const signature = {
        r: '0x' + 'r'.repeat(64) as `0x${string}`,
        s: '0x' + 's'.repeat(64) as `0x${string}`,
        v: BigInt(27),
      };

      const result = await builder.attachSignature(builtTx, signature);

      expect(result.transaction.signature).toEqual(signature);
      expect(result.signedTransaction).toBeDefined();
      expect(result.signedTransaction.length).toBeGreaterThan(builtTx.unsignedData.length);
    });
  });

  describe('multisig strategy', () => {
    it('should set and get multisig strategy', () => {
      const strategy: MultisigStrategy = {
        id: 'test',
        name: 'Test Strategy',
        threshold: 2,
        owners: ['0x0000000000000000000000000000000000000001'],
        validateSignatures: jest.fn().mockResolvedValue(true),
        combineSignatures: jest.fn().mockResolvedValue('0x' as `0x${string}`),
      };

      builder.setMultisigStrategy(strategy);
      expect(builder.getMultisigStrategy()).toEqual(strategy);
    });
  });

  describe('gas optimization', () => {
    it('should apply gas optimization when enabled', async () => {
      builder.setGasOptimizationConfig({
        enabled: true,
        speed: 'fast',
        gasLimitBuffer: 20,
        priorityFeeBoost: 10,
      });

      const builtTx = await builder.buildTransaction({
        chainId: 1,
        from: '0x0000000000000000000000000000000000000001',
        to: '0x0000000000000000000000000000000000000002',
        value: BigInt(0),
        gasLimit: BigInt(100000),
        maxFeePerGas: BigInt('30000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
      });

      const gasEstimate = {
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
      expect(optimized.transaction.maxPriorityFeePerGas).toBe(BigInt('2200000000'));
    });

    it('should not apply gas optimization when disabled', async () => {
      builder.setGasOptimizationConfig({
        enabled: false,
        speed: 'standard',
      });

      const builtTx = await builder.buildTransaction({
        chainId: 1,
        from: '0x0000000000000000000000000000000000000001',
        to: '0x0000000000000000000000000000000000000002',
        value: BigInt(0),
        gasLimit: BigInt(100000),
      });

      const gasEstimate = {
        gasLimit: BigInt(100000),
        baseFeePerGas: BigInt('20000000000'),
        maxPriorityFeePerGas: BigInt('2000000000'),
        maxFeePerGas: BigInt('42000000000'),
        estimatedCost: BigInt('4200000000000000'),
        confidence: 0.9,
        timestamp: new Date().toISOString(),
      };

      const optimized = await builder.applyGasOptimization(builtTx, gasEstimate);
      expect(optimized.transaction.gasLimit).toBe(BigInt(100000));
    });
  });

  describe('buildContractCall', () => {
    it('should build contract call transaction', async () => {
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
        '0x0000000000000000000000000000000000000001',
        '0x0000000000000000000000000000000000000002',
        'transfer',
        ['0x0000000000000000000000000000000000000003', BigInt('1000000000000000000')],
        abi
      );

      expect(result).toBeDefined();
      expect(result.transaction.to).toBe('0x0000000000000000000000000000000000000002');
      expect(result.transaction.data).toBeDefined();
      expect(result.transaction.data.length).toBeGreaterThan(2);
    });
  });
});
