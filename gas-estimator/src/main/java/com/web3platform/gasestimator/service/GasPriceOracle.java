package com.web3platform.gasestimator.service;

import com.web3platform.gasestimator.config.GasEstimatorConfig;
import com.web3platform.gasestimator.model.FeeHistoryPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasPriceOracle {

    private final GasEstimatorConfig gasEstimatorConfig;
    private final FeeHistoryCollector feeHistoryCollector;
    private final NetworkAnalyzer networkAnalyzer;

    public BigInteger getLegacyGasPrice(String chainId, String speed) {
        double multiplier = getSpeedMultiplier(speed);
        List<FeeHistoryPoint> history = feeHistoryCollector.collectFeeHistory(chainId, 20);

        if (history.isEmpty()) {
            return getDefaultGasPrice(speed);
        }

        FeeHistoryPoint latest = history.get(history.size() - 1);
        BigInteger baseFee = latest.getBaseFee();
        BigInteger avgPriorityFee = calculateAveragePriorityFee(latest.getReward());
        BigInteger estimatedGasPrice = baseFee.add(avgPriorityFee);

        BigInteger adjustedGasPrice = multiplyBigInteger(estimatedGasPrice, multiplier);
        log.debug("Legacy gas price for chain {} speed {}: {}", chainId, speed, adjustedGasPrice);
        return adjustedGasPrice;
    }

    public Map<String, BigInteger> getEip1559Fees(String chainId, String speed) {
        double multiplier = getSpeedMultiplier(speed);
        List<FeeHistoryPoint> history = feeHistoryCollector.collectFeeHistory(chainId, 20);

        BigInteger baseFee;
        BigInteger maxPriorityFeePerGas;

        if (history.isEmpty()) {
            baseFee = new BigInteger("1000000000");
            maxPriorityFeePerGas = gasEstimatorConfig.getEip1559PriorityFee()
                    .getOrDefault(speed, new BigInteger("2000000000"));
        } else {
            FeeHistoryPoint latest = history.get(history.size() - 1);
            baseFee = latest.getBaseFee();
            BigInteger avgPriorityFee = calculateAveragePriorityFee(latest.getReward());
            BigInteger configuredPriorityFee = gasEstimatorConfig.getEip1559PriorityFee()
                    .getOrDefault(speed, new BigInteger("2000000000"));
            maxPriorityFeePerGas = avgPriorityFee.max(configuredPriorityFee);
            maxPriorityFeePerGas = multiplyBigInteger(maxPriorityFeePerGas, multiplier);
        }

        BigInteger baseFeeWithBuffer = multiplyBigInteger(baseFee, 1.5);
        BigInteger maxFeePerGas = baseFeeWithBuffer.add(maxPriorityFeePerGas);

        log.debug("EIP-1559 fees for chain {} speed {}: maxFee={}, maxPriorityFee={}",
                chainId, speed, maxFeePerGas, maxPriorityFeePerGas);

        return Map.of(
                "maxFeePerGas", maxFeePerGas,
                "maxPriorityFeePerGas", maxPriorityFeePerGas,
                "baseFee", baseFee
        );
    }

    public double calculateConfidence(String chainId, String speed) {
        try {
            List<FeeHistoryPoint> history = feeHistoryCollector.collectFeeHistory(chainId, 20);

            if (history.size() < 5) {
                return 0.5;
            }

            double volatility = calculateVolatility(history);
            String congestionLevel = networkAnalyzer.estimateCongestionLevel(chainId);

            double baseConfidence = 0.8;

            if (volatility > 0.3) {
                baseConfidence -= 0.2;
            } else if (volatility > 0.15) {
                baseConfidence -= 0.1;
            }

            switch (congestionLevel) {
                case "EXTREME":
                    baseConfidence -= 0.2;
                    break;
                case "HIGH":
                    baseConfidence -= 0.1;
                    break;
                case "MEDIUM":
                    baseConfidence -= 0.05;
                    break;
                default:
                    break;
            }

            switch (speed) {
                case "URGENT":
                    baseConfidence += 0.1;
                    break;
                case "FAST":
                    baseConfidence += 0.05;
                    break;
                case "SLOW":
                    baseConfidence -= 0.1;
                    break;
                default:
                    break;
            }

            return Math.max(0.1, Math.min(0.99, baseConfidence));
        } catch (Exception e) {
            log.warn("Failed to calculate confidence for chain {}: {}", chainId, e.getMessage());
            return 0.5;
        }
    }

    private double getSpeedMultiplier(String speed) {
        return gasEstimatorConfig.getSpeedMultipliers()
                .getOrDefault(speed, gasEstimatorConfig.getSpeedMultipliers().get("NORMAL"));
    }

    private BigInteger getDefaultGasPrice(String speed) {
        return switch (speed) {
            case "SLOW" -> new BigInteger("1000000000");
            case "FAST" -> new BigInteger("3000000000");
            case "URGENT" -> new BigInteger("5000000000");
            default -> new BigInteger("2000000000");
        };
    }

    private BigInteger calculateAveragePriorityFee(List<BigInteger> rewards) {
        if (rewards == null || rewards.isEmpty()) {
            return new BigInteger("1000000000");
        }
        BigInteger sum = BigInteger.ZERO;
        for (BigInteger reward : rewards) {
            sum = sum.add(reward);
        }
        return sum.divide(BigInteger.valueOf(rewards.size()));
    }

    private BigInteger multiplyBigInteger(BigInteger value, double multiplier) {
        BigDecimal bdValue = new BigDecimal(value);
        BigDecimal bdMultiplier = BigDecimal.valueOf(multiplier);
        return bdValue.multiply(bdMultiplier).toBigInteger();
    }

    private double calculateVolatility(List<FeeHistoryPoint> history) {
        if (history.size() < 2) {
            return 0.0;
        }

        double sum = 0.0;
        double sumSq = 0.0;
        int n = history.size();

        for (FeeHistoryPoint point : history) {
            double gasUsedRatio = point.getGasUsedRatio();
            sum += gasUsedRatio;
            sumSq += gasUsedRatio * gasUsedRatio;
        }

        double mean = sum / n;
        double variance = (sumSq / n) - (mean * mean);
        return Math.sqrt(variance);
    }
}
