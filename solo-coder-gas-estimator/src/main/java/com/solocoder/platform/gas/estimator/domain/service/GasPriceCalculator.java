package com.solocoder.platform.gas.estimator.domain.service;

import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;
import com.solocoder.platform.gas.estimator.domain.model.GasHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GasPriceCalculator {

    private static final BigDecimal DEFAULT_BASE_FEE = new BigDecimal("1000000000");
    private static final BigDecimal DEFAULT_PRIORITY_FEE = new BigDecimal("100000000");

    public BigDecimal calculateBaseFee(List<GasHistory> historyData) {
        if (historyData == null || historyData.isEmpty()) {
            return DEFAULT_BASE_FEE;
        }
        return historyData.stream()
                .filter(h -> h.getBaseFee() != null)
                .map(GasHistory::getBaseFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(historyData.size()), RoundingMode.HALF_UP);
    }

    public GasEstimation.GasPriceLevel calculateGasPriceLevels(List<GasHistory> historyData, BigDecimal baseFee) {
        if (historyData == null || historyData.isEmpty()) {
            return GasEstimation.GasPriceLevel.builder()
                    .low(baseFee.multiply(new BigDecimal("1.0")))
                    .medium(baseFee.multiply(new BigDecimal("1.5")))
                    .high(baseFee.multiply(new BigDecimal("2.0")))
                    .build();
        }

        List<BigDecimal> gasPrices = historyData.stream()
                .map(GasHistory::getGasPrice)
                .sorted()
                .toList();

        int size = gasPrices.size();
        BigDecimal low = gasPrices.get((int) (size * 0.25));
        BigDecimal medium = gasPrices.get((int) (size * 0.5));
        BigDecimal high = gasPrices.get((int) (size * 0.75));

        return GasEstimation.GasPriceLevel.builder()
                .low(low)
                .medium(medium)
                .high(high)
                .build();
    }

    public GasEstimation.PriorityFeeLevel calculatePriorityFeeLevels(List<GasHistory> historyData) {
        if (historyData == null || historyData.isEmpty()) {
            return GasEstimation.PriorityFeeLevel.builder()
                    .low(DEFAULT_PRIORITY_FEE)
                    .medium(DEFAULT_PRIORITY_FEE.multiply(new BigDecimal("2")))
                    .high(DEFAULT_PRIORITY_FEE.multiply(new BigDecimal("3")))
                    .build();
        }

        List<BigDecimal> priorityFees = historyData.stream()
                .filter(h -> h.getPriorityFee() != null)
                .map(GasHistory::getPriorityFee)
                .sorted()
                .toList();

        if (priorityFees.isEmpty()) {
            return GasEstimation.PriorityFeeLevel.builder()
                    .low(DEFAULT_PRIORITY_FEE)
                    .medium(DEFAULT_PRIORITY_FEE.multiply(new BigDecimal("2")))
                    .high(DEFAULT_PRIORITY_FEE.multiply(new BigDecimal("3")))
                    .build();
        }

        int size = priorityFees.size();
        BigDecimal low = priorityFees.get((int) (size * 0.25));
        BigDecimal medium = priorityFees.get((int) (size * 0.5));
        BigDecimal high = priorityFees.get((int) (size * 0.75));

        return GasEstimation.PriorityFeeLevel.builder()
                .low(low)
                .medium(medium)
                .high(high)
                .build();
    }

    public GasEstimation.NetworkStatus calculateNetworkStatus(List<GasHistory> historyData) {
        if (historyData == null || historyData.isEmpty()) {
            return GasEstimation.NetworkStatus.builder()
                    .pendingTransactions(100)
                    .blockGasUsed(15000000L)
                    .blockGasLimit(30000000L)
                    .gasUtilization(0.5)
                    .congestionLevel(GasEstimation.NetworkStatus.CongestionLevel.NORMAL)
                    .build();
        }

        GasHistory latest = historyData.stream()
                .max(Comparator.comparing(GasHistory::getBlockNumber))
                .orElse(null);

        if (latest == null) {
            return GasEstimation.NetworkStatus.builder()
                    .pendingTransactions(100)
                    .blockGasUsed(15000000L)
                    .blockGasLimit(30000000L)
                    .gasUtilization(0.5)
                    .congestionLevel(GasEstimation.NetworkStatus.CongestionLevel.NORMAL)
                    .build();
        }

        long gasUsed = latest.getGasUsed() != null ? latest.getGasUsed() : 15000000L;
        long gasLimit = latest.getGasLimit() != null ? latest.getGasLimit() : 30000000L;
        double utilization = gasLimit > 0 ? (double) gasUsed / gasLimit : 0.5;

        GasEstimation.NetworkStatus.CongestionLevel congestionLevel;
        if (utilization < 0.3) {
            congestionLevel = GasEstimation.NetworkStatus.CongestionLevel.LOW;
        } else if (utilization < 0.6) {
            congestionLevel = GasEstimation.NetworkStatus.CongestionLevel.NORMAL;
        } else if (utilization < 0.9) {
            congestionLevel = GasEstimation.NetworkStatus.CongestionLevel.HIGH;
        } else {
            congestionLevel = GasEstimation.NetworkStatus.CongestionLevel.CONGESTED;
        }

        return GasEstimation.NetworkStatus.builder()
                .pendingTransactions(latest.getTransactionCount() != null ? latest.getTransactionCount() : 100)
                .blockGasUsed(gasUsed)
                .blockGasLimit(gasLimit)
                .gasUtilization(utilization)
                .congestionLevel(congestionLevel)
                .build();
    }
}
