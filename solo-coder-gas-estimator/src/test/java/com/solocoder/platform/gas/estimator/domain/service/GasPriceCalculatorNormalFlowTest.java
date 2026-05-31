package com.solocoder.platform.gas.estimator.domain.service;

import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;
import com.solocoder.platform.gas.estimator.domain.model.GasHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GasPriceCalculator - 正常业务流程测试")
class GasPriceCalculatorNormalFlowTest {

    @InjectMocks
    private GasPriceCalculator gasPriceCalculator;

    @Test
    @DisplayName("正常计算BaseFee - 使用历史数据平均值")
    void calculateBaseFee_WithValidHistoryData() {
        List<GasHistory> historyData = Arrays.asList(
                createGasHistory(new BigDecimal("1000000000"), 1L),
                createGasHistory(new BigDecimal("2000000000"), 2L),
                createGasHistory(new BigDecimal("3000000000"), 3L)
        );

        BigDecimal result = gasPriceCalculator.calculateBaseFee(historyData);

        assertEquals(new BigDecimal("2000000000"), result);
    }

    @Test
    @DisplayName("正常计算GasPrice分档 - 基于历史数据百分位")
    void calculateGasPriceLevels_WithValidHistoryData() {
        List<GasHistory> historyData = createGasHistoryWithPrices(
                new BigDecimal("1000000000"),
                new BigDecimal("2000000000"),
                new BigDecimal("3000000000"),
                new BigDecimal("4000000000"),
                new BigDecimal("5000000000"),
                new BigDecimal("6000000000"),
                new BigDecimal("7000000000"),
                new BigDecimal("8000000000")
        );

        GasEstimation.GasPriceLevel result = gasPriceCalculator.calculateGasPriceLevels(
                historyData, new BigDecimal("4000000000"));

        assertNotNull(result);
        assertNotNull(result.getLow());
        assertNotNull(result.getMedium());
        assertNotNull(result.getHigh());
        assertTrue(result.getLow().compareTo(result.getMedium()) <= 0);
        assertTrue(result.getMedium().compareTo(result.getHigh()) <= 0);
    }

    @Test
    @DisplayName("正常计算PriorityFee分档 - 基于历史数据百分位")
    void calculatePriorityFeeLevels_WithValidHistoryData() {
        List<GasHistory> historyData = createGasHistoryWithPriorityFees(
                new BigDecimal("100000000"),
                new BigDecimal("200000000"),
                new BigDecimal("300000000"),
                new BigDecimal("400000000"),
                new BigDecimal("500000000")
        );

        GasEstimation.PriorityFeeLevel result = gasPriceCalculator.calculatePriorityFeeLevels(historyData);

        assertNotNull(result);
        assertNotNull(result.getLow());
        assertNotNull(result.getMedium());
        assertNotNull(result.getHigh());
        assertTrue(result.getLow().compareTo(result.getMedium()) <= 0);
        assertTrue(result.getMedium().compareTo(result.getHigh()) <= 0);
    }

    @Test
    @DisplayName("正常计算网络状态 - 正常拥堵级别")
    void calculateNetworkStatus_NormalCongestion() {
        List<GasHistory> historyData = Arrays.asList(
                createGasHistoryWithNetwork(1L, 15000000L, 30000000L, 100)
        );

        GasEstimation.NetworkStatus result = gasPriceCalculator.calculateNetworkStatus(historyData);

        assertNotNull(result);
        assertEquals(GasEstimation.NetworkStatus.CongestionLevel.NORMAL, result.getCongestionLevel());
        assertEquals(0.5, result.getGasUtilization(), 0.001);
        assertEquals(15000000L, result.getBlockGasUsed());
        assertEquals(30000000L, result.getBlockGasLimit());
    }

    @Test
    @DisplayName("完整流程 - 基于历史数据执行完整的Gas预估计算")
    void fullGasEstimationFlow() {
        List<GasHistory> historyData = createGasHistoryWithPrices(
                new BigDecimal("1000000000"),
                new BigDecimal("2000000000"),
                new BigDecimal("3000000000"),
                new BigDecimal("4000000000"),
                new BigDecimal("5000000000")
        );

        BigDecimal baseFee = gasPriceCalculator.calculateBaseFee(historyData);
        GasEstimation.GasPriceLevel priceLevels = gasPriceCalculator.calculateGasPriceLevels(historyData, baseFee);
        GasEstimation.PriorityFeeLevel priorityLevels = gasPriceCalculator.calculatePriorityFeeLevels(historyData);
        GasEstimation.NetworkStatus networkStatus = gasPriceCalculator.calculateNetworkStatus(historyData);

        assertNotNull(baseFee);
        assertNotNull(priceLevels);
        assertNotNull(priorityLevels);
        assertNotNull(networkStatus);
    }

    private GasHistory createGasHistory(BigDecimal baseFee, Long blockNumber) {
        return GasHistory.builder()
                .blockNumber(blockNumber)
                .baseFee(baseFee)
                .gasPrice(baseFee)
                .build();
    }

    private List<GasHistory> createGasHistoryWithPrices(BigDecimal... prices) {
        return Arrays.stream(prices)
                .map(price -> GasHistory.builder()
                        .blockNumber((long) (Math.random() * 1000))
                        .baseFee(new BigDecimal("1000000000"))
                        .gasPrice(price)
                        .priorityFee(new BigDecimal("100000000"))
                        .build())
                .toList();
    }

    private List<GasHistory> createGasHistoryWithPriorityFees(BigDecimal... fees) {
        return Arrays.stream(fees)
                .map(fee -> GasHistory.builder()
                        .blockNumber((long) (Math.random() * 1000))
                        .baseFee(new BigDecimal("1000000000"))
                        .gasPrice(new BigDecimal("2000000000"))
                        .priorityFee(fee)
                        .build())
                .toList();
    }

    private GasHistory createGasHistoryWithNetwork(Long blockNumber, Long gasUsed, Long gasLimit, Integer txCount) {
        return GasHistory.builder()
                .blockNumber(blockNumber)
                .gasUsed(gasUsed)
                .gasLimit(gasLimit)
                .transactionCount(txCount)
                .baseFee(new BigDecimal("1000000000"))
                .gasPrice(new BigDecimal("2000000000"))
                .build();
    }
}
