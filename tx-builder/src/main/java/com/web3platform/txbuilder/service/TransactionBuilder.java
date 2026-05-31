package com.web3platform.txbuilder.service;

import com.web3platform.txbuilder.model.GasOptimizationParams;
import com.web3platform.txbuilder.model.TransactionBuildRequest;
import com.web3platform.txbuilder.util.ChainIdResolver;
import com.web3platform.txbuilder.util.NonceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.crypto.RawTransaction;
import org.web3j.utils.Convert;

import java.math.BigInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionBuilder {

    private final NonceManager nonceManager;
    private final ChainIdResolver chainIdResolver;
    private final GasOptimizationService gasOptimizationService;

    public RawTransaction buildEvmTransaction(TransactionBuildRequest request) {
        log.info("Building EVM transaction for chain: {}, from: {}, to: {}",
                request.getChainId(), request.getFromAddress(), request.getToAddress());

        BigInteger chainId = chainIdResolver.resolveToBigInteger(request.getChainId());

        BigInteger nonce = setNonce(request);
        BigInteger gasPrice = calculateGasPrice(request, null);
        BigInteger gasLimit = estimateGasLimit(request);
        BigInteger value = request.getValue() != null ? request.getValue() : BigInteger.ZERO;
        String data = request.getData() != null ? request.getData() : "";

        return RawTransaction.createTransaction(
                nonce,
                gasPrice,
                gasLimit,
                request.getToAddress(),
                value,
                data
        );
    }

    public Object buildTypedTransaction(TransactionBuildRequest request) {
        log.info("Building typed transaction of type: {}", request.getTxType());

        String txType = request.getTxType() != null ? request.getTxType().toUpperCase() : "LEGACY";
        BigInteger chainId = chainIdResolver.resolveToBigInteger(request.getChainId());
        BigInteger nonce = setNonce(request);
        BigInteger gasLimit = estimateGasLimit(request);
        BigInteger value = request.getValue() != null ? request.getValue() : BigInteger.ZERO;
        String data = request.getData() != null ? request.getData() : "";

        return switch (TransactionBuildRequest.TxType.valueOf(txType)) {
            case LEGACY -> buildLegacyTransaction(request, nonce, gasLimit, value, data);
            case EIP1559 -> buildEip1559Transaction(request, chainId, nonce, gasLimit, value, data);
            case EIP2930 -> buildEip2930Transaction(request, chainId, nonce, gasLimit, value, data);
        };
    }

    private RawTransaction buildLegacyTransaction(TransactionBuildRequest request,
                                                   BigInteger nonce,
                                                   BigInteger gasLimit,
                                                   BigInteger value,
                                                   String data) {
        BigInteger gasPrice = calculateGasPrice(request, null);
        return RawTransaction.createTransaction(
                nonce,
                gasPrice,
                gasLimit,
                request.getToAddress(),
                value,
                data
        );
    }

    private RawTransaction buildEip1559Transaction(TransactionBuildRequest request,
                                                    BigInteger chainId,
                                                    BigInteger nonce,
                                                    BigInteger gasLimit,
                                                    BigInteger value,
                                                    String data) {
        GasOptimizationParams params = GasOptimizationParams.builder()
                .maxPriorityFee(request.getGasPrice())
                .maxFeePerGas(request.getGasPrice())
                .build();

        GasOptimizationParams optimizedParams = gasOptimizationService.implementEip1559(request, params);

        return RawTransaction.createEtherTransaction(
                chainId.longValue(),
                nonce,
                gasLimit,
                request.getToAddress(),
                value,
                optimizedParams.getMaxPriorityFee(),
                optimizedParams.getMaxFeePerGas()
        );
    }

    private RawTransaction buildEip2930Transaction(TransactionBuildRequest request,
                                                    BigInteger chainId,
                                                    BigInteger nonce,
                                                    BigInteger gasLimit,
                                                    BigInteger value,
                                                    String data) {
        BigInteger gasPrice = calculateGasPrice(request, null);
        return RawTransaction.createTransaction(
                chainId.longValue(),
                nonce,
                gasPrice,
                gasLimit,
                request.getToAddress(),
                value,
                data,
                null
        );
    }

    public BigInteger estimateGasLimit(TransactionBuildRequest request) {
        if (request.getGasLimit() != null && request.getGasLimit() > 0) {
            return BigInteger.valueOf(request.getGasLimit());
        }

        log.info("Estimating gas limit for transaction to: {}", request.getToAddress());

        try {
            BigInteger estimatedGas = estimateGasFromNetwork(request);
            BigInteger buffer = estimatedGas.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
            log.info("Estimated gas: {}, with buffer: {}", estimatedGas, buffer);
            return buffer;
        } catch (Exception e) {
            log.warn("Failed to estimate gas from network, using default: 21000", e);
            return BigInteger.valueOf(21000);
        }
    }

    private BigInteger estimateGasFromNetwork(TransactionBuildRequest request) {
        return BigInteger.valueOf(21000);
    }

    public BigInteger setNonce(TransactionBuildRequest request) {
        if (request.getNonce() != null && request.getNonce() >= 0) {
            return BigInteger.valueOf(request.getNonce());
        }

        log.info("Getting nonce for address: {} on chain: {}", request.getFromAddress(), request.getChainId());
        return nonceManager.getNextNonce(request.getChainId(), request.getFromAddress());
    }

    public BigInteger calculateGasPrice(TransactionBuildRequest request, GasOptimizationParams optimizationParams) {
        if (request.getGasPrice() != null && request.getGasPrice().compareTo(BigInteger.ZERO) > 0) {
            return request.getGasPrice();
        }

        log.info("Calculating gas price for chain: {}", request.getChainId());

        if (optimizationParams != null) {
            GasOptimizationParams optimized = gasOptimizationService.suggestGasParams(
                    request.getChainId(),
                    optimizationParams.getSpeed()
            );
            if (optimized.getMaxFeePerGas() != null) {
                return optimized.getMaxFeePerGas();
            }
        }

        BigInteger baseGasPrice = getBaseGasPrice(request.getChainId());
        log.info("Base gas price: {} wei", baseGasPrice);
        return baseGasPrice;
    }

    private BigInteger getBaseGasPrice(String chainId) {
        try {
            long id = chainIdResolver.resolveToLong(chainId);
            return switch ((int) id) {
                case 1 -> Convert.toWei("20", Convert.Unit.GWEI).toBigInteger();
                case 137 -> Convert.toWei("30", Convert.Unit.GWEI).toBigInteger();
                case 56 -> Convert.toWei("5", Convert.Unit.GWEI).toBigInteger();
                case 42161 -> Convert.toWei("0.1", Convert.Unit.GWEI).toBigInteger();
                case 10 -> Convert.toWei("0.001", Convert.Unit.GWEI).toBigInteger();
                default -> Convert.toWei("10", Convert.Unit.GWEI).toBigInteger();
            };
        } catch (Exception e) {
            log.warn("Failed to get base gas price for chain: {}, using default", chainId);
            return Convert.toWei("10", Convert.Unit.GWEI).toBigInteger();
        }
    }
}
