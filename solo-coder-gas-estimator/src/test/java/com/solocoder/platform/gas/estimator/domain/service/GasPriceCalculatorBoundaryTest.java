package com.solocoder.platform.gas.estimator.domain.service;

import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;
import com.solocoder.platform.gas.estimator.domain.model.GasHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GasPriceCalculator - 边界值测试")
class GasPriceCalculatorBoundaryTest {

    @InjectMocks
    private GasPriceCalculator gasPriceCalculator;

    @Test
    @DisplayName("边界值 - 空历史数据计算BaseFee，返回默认值")
    void calculateBaseFee_EmptyHistory() {
        BigDecimal result = gasPriceCalculator.calculateBaseFee(Collections.emptyList());

        assertEquals(new BigDecimal("1000000000"), result);
    }

    @Test
    @DisplayName("边界值 - null历史数据计算BaseFee，返回默认值")
    void calculateBaseFee_NullHistory() {
        BigDecimal result = gasPriceCalculator.calculateBaseFee(null);

        assertEquals(new BigDecimal("1000000000"), result);
    }

    @Test
    @DisplayName("边界值 - 单条历史数据计算BaseFee")
    void calculateBaseFee_SingleDataPoint() {
        List<GasHistory> historyData = Collections.singletonList(
                GasHistory.builder()
                        .blockNumber(1L)
                        .baseFee(new BigDecimal("5000000000"))
                        .gasPrice(new BigDecimal("5000000000"))
                        .build()
        );

        BigDecimal result = gasPriceCalculator.calculateBaseFee(historyData);

        assertEquals(new BigDecimal("5000000000"), result);
    }

    @Test
    @DisplayName("边界值 - 空历史数据计算GasPrice分档，使用默认比例")
    void calculateGasPriceLevels_EmptyHistory() {
        BigDecimal baseFee = new BigDecimal("2000000000");

        GasEstimation.GasPriceLevel result = gasPriceCalculator.calculateGasPriceLevels(
                Collections.emptyList(), baseFee);

        assertNotNull(result);
        assertEquals(baseFee.multiply(new BigDecimal("1.0")), result.getLow());
        assertEquals(baseFee.multiply(new BigDecimal("1.5")), result.getMedium());
        assertEquals(baseFee.multiply(new BigDecimal("2.0")), result.getHigh());
    }

    @Test
    @DisplayName("边界值 - null历史数据计算GasPrice分档")
    void calculateGasPriceLevels_NullHistory() {
        BigDecimal baseFee = new BigDecimal("2000000000");

        GasEstimation.GasPriceLevel result = gasPriceCalculator.calculateGasPriceLevels(null, baseFee);

        assertNotNull(result);
        assertEquals(baseFee.multiply(new BigDecimal("1.0")), result.getLow());
        assertEquals(baseFee.multiply(new BigDecimal("1.5")), result.getMedium());
        assertEquals(baseFee.multiply(new BigDecimal("2.0")), result.getHigh());
    }

    @Test
    @DisplayName("边界值 - 空历史数据计算PriorityFee分档，返回默认值")
    void calculatePriorityFeeLevels_EmptyHistory() {
        GasEstimation.PriorityFeeLevel result = gasPriceCalculator.calculatePriorityFeeLevels(Collections.emptyList());

        assertNotNull(result);
        assertEquals(new BigDecimal("100000000"), result.getLow());
        assertEquals(new BigDecimal("200000000"), result.getMedium());
        assertEquals(new BigDecimal("300000000"), result.getHigh());
    }

    @Test
    @DisplayName("边界值 - 历史数据中PriorityFee全为null")
    void calculatePriorityFeeLevels_AllNullPriorityFees() {
        List<GasHistory> historyData = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            historyData.add(GasHistory.builder()
                    .blockNumber((long) i)
                    .baseFee(new BigDecimal("1000000000"))
                    .gasPrice(new BigDecimal("2000000000"))
                    .priorityFee(null)
                    .build());
        }

        GasEstimation.PriorityFeeLevel result = gasPriceCalculator.calculatePriorityFeeLevels(historyData);

        assertNotNull(result);
        assertEquals(new BigDecimal("100000000"), result.getLow());
        assertEquals(new BigDecimal("200000000"), result.getMedium());
        assertEquals(new BigDecimal("300000000"), result.getHigh());
    }

    @Test
    @DisplayName("边界值 - 网络状态：低拥堵（利用率<30%）")
    void calculateNetworkStatus_LowCongestion() {
        List<GasHistory> historyData = Collections.singletonList(
                createGasHistoryWithNetwork(1L, 8000000L, 30000000L, 50)
        );

        GasEstimation.NetworkStatus result = gasPriceCalculator.calculateNetworkStatus(historyData);

        assertEquals(GasEstimation.NetworkStatus.CongestionLevel.LOW, result.getCongestionLevel());
        assertTrue(result.getGasUtilization() < 0.3);
    }

    @Test
    @DisplayName("边界值 - 网络状态：高拥堵（利用率60%-90%）")
    void calculateNetworkStatus_HighCongestion() {
        List<GasHistory> historyData = Collections.singletonList(
                createGasHistoryWithNetwork(1L, 25000000L, 30000000L, 500)
        );

        GasEstimation.NetworkStatus result = gasPriceCalculator.calculateNetworkStatus(historyData);

        assertEquals(GasEstimation.NetworkStatus.CongestionLevel.HIGH, result.getCongestionLevel());
        assertTrue(result.getGasUtilization() >= 0.6 && result.getGasUtilization() < 0.9);
    }

    @Test
    @DisplayName("边界值 - 网络状态：极度拥堵（利用率>=90%）")
    void calculateNetworkStatus_Congested() {
        List<GasHistory> historyData = Collections.singletonList(
                createGasHistoryWithNetwork(1L, 29000000L, 30000000L, 1000)
        );

        GasEstimation.NetworkStatus result = gasPriceCalculator.calculateNetworkStatus(historyData);

        assertEquals(GasEstimation.NetworkStatus.CongestionLevel.CONGESTED, result.getCongestionLevel());
        assertTrue(result.getGasUtilization() >= 0.9);
    }

    @Test
    @DisplayName("边界值 - Gas Limit为0时的处理")
    void calculateNetworkStatus_ZeroGasLimit() {
        List<GasHistory> historyData = Collections.singletonList(
                createGasHistoryWithNetwork(1L, 15000000L, 0L, 100)
        );

        GasEstimation.NetworkStatus result = gasPriceCalculator.calculateNetworkStatus(historyData);

        assertNotNull(result);
        assertEquals(0.5, result.getGasUtilization(), 0.001);
    }

    @Test
    @DisplayName("边界值 - 历史数据中BaseFee全为null")
    void calculateBaseFee_AllNullBaseFees() {
        List<GasHistory> historyData = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            historyData.add(GasHistory.builder()
                    .blockNumber((long) i)
                    .baseFee(null)
                    .gasPrice(new BigDecimal("2000000000"))
                    .build());
        }

        BigDecimal result = gasPriceCalculator.calculateBaseFee(historyData);

        assertEquals(new BigDecimal("1000000000"), result);
    }

    @Test
    @DisplayName("边界值 - 超大Gas价格值")
    void calculateGasPriceLevels_VeryHighPrices() {
        List<GasHistory> historyData = Collections.singletonList(
                GasHistory.builder()
                        .blockNumber(1L)
                        .baseFee(new BigDecimal("999999999999999999"))
                        .gasPrice(new BigDecimal("999999999999999999"))
                        .priorityFee(new BigDecimal("100000000000000000"))
                        .build()
        );

        BigDecimal baseFee = gasPriceCalculator.calculateBaseFee(historyData);
        GasEstimation.GasPriceLevel levels = gasPriceCalculator.calculateGasPriceLevels(historyData, baseFee);

        assertNotNull(baseFee);
        assertNotNull(levels);
        assertEquals(new BigDecimal("999999999999999999"), baseFee);
    }

    @Test
    @DisplayName("边界值 - 极小Gas价格值")
    void calculateGasPriceLevels_VeryLowPrices() {
        List<GasHistory> historyData = Collections.singletonList(
                GasHistory.builder()
                        .blockNumber(1L)
                        .baseFee(new BigDecimal("1"))
                        .gasPrice(new BigDecimal("1"))
                        .priorityFee(new BigDecimal("1"))
                        .build()
        );

        BigDecimal baseFee = gasPriceCalculator.calculateBaseFee(historyData);

        assertEquals(new BigDecimal("1"), baseFee);
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
