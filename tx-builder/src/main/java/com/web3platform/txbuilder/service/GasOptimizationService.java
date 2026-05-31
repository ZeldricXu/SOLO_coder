package com.web3platform.txbuilder.service;

import com.web3platform.txbuilder.model.GasOptimizationParams;
import com.web3platform.txbuilder.model.TransactionBuildRequest;
import com.web3platform.txbuilder.util.ChainIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.utils.Convert;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasOptimizationService {

    private final ChainIdResolver chainIdResolver;

    private final Map<String, BigInteger> baseFeeCache = new ConcurrentHashMap<>();
    private final Map<String, BigInteger> priorityFeeCache = new ConcurrentHashMap<>();

    public TransactionBuildRequest optimizeTransaction(TransactionBuildRequest request, GasOptimizationParams params) {
        log.info("Optimizing transaction gas for chain: {}, speed: {}", request.getChainId(), params.getSpeed());

        GasOptimizationParams optimizedParams = suggestGasParams(request.getChainId(), params.getSpeed());

        if (params.getMaxPriorityFee() != null && params.getMaxPriorityFee().compareTo(BigInteger.ZERO) > 0) {
            optimizedParams.setMaxPriorityFee(params.getMaxPriorityFee());
        }
        if (params.getMaxFeePerGas() != null && params.getMaxFeePerGas().compareTo(BigInteger.ZERO) > 0) {
            optimizedParams.setMaxFeePerGas(params.getMaxFeePerGas());
        }

        if (params.getDeadline() > 0) {
            optimizedParams.setDeadline(params.getDeadline());
        }

        String txType = request.getTxType() != null ? request.getTxType().toUpperCase() : "LEGACY";
        if ("EIP1559".equals(txType) || chainIdResolver.isEip1559Supported(request.getChainId())) {
            request.setGasPrice(optimizedParams.getMaxFeePerGas());
        } else {
            request.setGasPrice(optimizedParams.getMaxFeePerGas());
        }

        log.info("Optimized gas price: {} wei", request.getGasPrice());
        return request;
    }

    public List<TransactionBuildRequest> batchOptimize(List<TransactionBuildRequest> requests) {
        log.info("Batch optimizing {} transactions", requests.size());
        return requests.stream()
                .map(request -> optimizeTransaction(request,
                        GasOptimizationParams.builder()
                                .speed(GasOptimizationParams.Speed.NORMAL.name())
                                .build()))
                .toList();
    }

    public GasOptimizationParams suggestGasParams(String chainId, String speed) {
        log.info("Suggesting gas params for chain: {}, speed: {}", chainId, speed);

        GasOptimizationParams.Speed speedLevel = parseSpeed(speed);

        BigInteger baseFee = getBaseFee(chainId);
        BigInteger priorityFee = getPriorityFee(chainId, speedLevel);
        BigInteger maxFeePerGas = calculateMaxFeePerGas(baseFee, priorityFee, speedLevel);

        return GasOptimizationParams.builder()
                .speed(speedLevel.name())
                .maxPriorityFee(priorityFee)
                .maxFeePerGas(maxFeePerGas)
                .deadline(calculateDeadline(speedLevel))
                .build();
    }

    public GasOptimizationParams implementEip1559(TransactionBuildRequest request, GasOptimizationParams params) {
        log.info("Implementing EIP-1559 for chain: {}", request.getChainId());

        if (!chainIdResolver.isEip1559Supported(request.getChainId())) {
            log.warn("Chain {} does not support EIP-1559, falling back to legacy", request.getChainId());
            return params;
        }

        BigInteger baseFee = getBaseFee(request.getChainId());
        BigInteger priorityFee = params.getMaxPriorityFee();
        BigInteger maxFee = params.getMaxFeePerGas();

        if (priorityFee == null || priorityFee.compareTo(BigInteger.ZERO) <= 0) {
            priorityFee = getPriorityFee(request.getChainId(), GasOptimizationParams.Speed.NORMAL);
        }

        if (maxFee == null || maxFee.compareTo(BigInteger.ZERO) <= 0) {
            maxFee = baseFee.multiply(BigInteger.valueOf(2)).add(priorityFee);
        }

        return GasOptimizationParams.builder()
                .speed(params.getSpeed())
                .maxPriorityFee(priorityFee)
                .maxFeePerGas(maxFee)
                .deadline(params.getDeadline())
                .build();
    }

    public BigInteger getBaseFee(String chainId) {
        try {
            BigInteger cached = baseFeeCache.get(chainId);
            if (cached != null) {
                return cached;
            }

            BigInteger baseFee = fetchBaseFeeFromNetwork(chainId);
            baseFeeCache.put(chainId, baseFee);
            return baseFee;
        } catch (Exception e) {
            log.warn("Failed to get base fee from network, using default for chain: {}", chainId, e);
            return getDefaultBaseFee(chainId);
        }
    }

    public void refreshGasPrices(String chainId) {
        log.info("Refreshing gas prices for chain: {}", chainId);
        try {
            baseFeeCache.remove(chainId);
            priorityFeeCache.clear();
            getBaseFee(chainId);
            log.info("Gas prices refreshed successfully for chain: {}", chainId);
        } catch (Exception e) {
            log.error("Failed to refresh gas prices for chain: {}", chainId, e);
        }
    }

    private GasOptimizationParams.Speed parseSpeed(String speed) {
        if (speed == null) {
            return GasOptimizationParams.Speed.NORMAL;
        }
        try {
            return GasOptimizationParams.Speed.valueOf(speed.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid speed: {}, using NORMAL", speed);
            return GasOptimizationParams.Speed.NORMAL;
        }
    }

    private BigInteger getPriorityFee(String chainId, GasOptimizationParams.Speed speed) {
        String cacheKey = chainId + ":" + speed.name();
        BigInteger cached = priorityFeeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        BigInteger basePriorityFee = getDefaultPriorityFee(chainId);
        BigInteger priorityFee = switch (speed) {
            case SLOW -> basePriorityFee.multiply(BigInteger.valueOf(70)).divide(BigInteger.valueOf(100));
            case NORMAL -> basePriorityFee;
            case FAST -> basePriorityFee.multiply(BigInteger.valueOf(150)).divide(BigInteger.valueOf(100));
            case URGENT -> basePriorityFee.multiply(BigInteger.valueOf(200)).divide(BigInteger.valueOf(100));
        };

        priorityFeeCache.put(cacheKey, priorityFee);
        return priorityFee;
    }

    private BigInteger calculateMaxFeePerGas(BigInteger baseFee, BigInteger priorityFee, GasOptimizationParams.Speed speed) {
        BigInteger multiplier = switch (speed) {
            case SLOW -> BigInteger.valueOf(110);
            case NORMAL -> BigInteger.valueOf(150);
            case FAST -> BigInteger.valueOf(200);
            case URGENT -> BigInteger.valueOf(300);
        };

        return baseFee.multiply(multiplier).divide(BigInteger.valueOf(100)).add(priorityFee);
    }

    private long calculateDeadline(GasOptimizationParams.Speed speed) {
        return switch (speed) {
            case SLOW -> 3600;
            case NORMAL -> 1800;
            case FAST -> 600;
            case URGENT -> 300;
        };
    }

    private BigInteger fetchBaseFeeFromNetwork(String chainId) {
        return getDefaultBaseFee(chainId);
    }

    private BigInteger getDefaultBaseFee(String chainId) {
        try {
            long id = chainIdResolver.resolveToLong(chainId);
            return switch ((int) id) {
                case 1 -> Convert.toWei("20", Convert.Unit.GWEI).toBigInteger();
                case 11155111 -> Convert.toWei("10", Convert.Unit.GWEI).toBigInteger();
                case 137 -> Convert.toWei("25", Convert.Unit.GWEI).toBigInteger();
                case 56 -> Convert.toWei("3", Convert.Unit.GWEI).toBigInteger();
                case 42161 -> Convert.toWei("0.05", Convert.Unit.GWEI).toBigInteger();
                case 10 -> Convert.toWei("0.001", Convert.Unit.GWEI).toBigInteger();
                default -> Convert.toWei("10", Convert.Unit.GWEI).toBigInteger();
            };
        } catch (Exception e) {
            return Convert.toWei("10", Convert.Unit.GWEI).toBigInteger();
        }
    }

    private BigInteger getDefaultPriorityFee(String chainId) {
        try {
            long id = chainIdResolver.resolveToLong(chainId);
            return switch ((int) id) {
                case 1 -> Convert.toWei("2", Convert.Unit.GWEI).toBigInteger();
                case 137 -> Convert.toWei("30", Convert.Unit.GWEI).toBigInteger();
                case 56 -> Convert.toWei("1", Convert.Unit.GWEI).toBigInteger();
                case 42161 -> Convert.toWei("0.01", Convert.Unit.GWEI).toBigInteger();
                case 10 -> Convert.toWei("0.0001", Convert.Unit.GWEI).toBigInteger();
                default -> Convert.toWei("1", Convert.Unit.GWEI).toBigInteger();
            };
        } catch (Exception e) {
            return Convert.toWei("1", Convert.Unit.GWEI).toBigInteger();
        }
    }
}
