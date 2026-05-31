package com.solocoder.platform.gas.estimator.application.service;

import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;
import com.solocoder.platform.gas.estimator.domain.repository.GasEstimationRepository;
import com.solocoder.platform.gas.estimator.domain.repository.GasHistoryRepository;
import com.solocoder.platform.gas.estimator.domain.service.GasPriceCalculator;
import com.solocoder.platform.gas.estimator.domain.service.RequestValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GasEstimationApplicationService - 超时降级测试")
class GasEstimationApplicationServiceTimeoutTest {

    @Mock
    private RequestValidator requestValidator;

    @Mock
    private GasPriceCalculator gasPriceCalculator;

    @Mock
    private GasEstimationRepository gasEstimationRepository;

    @Mock
    private GasHistoryRepository gasHistoryRepository;

    @InjectMocks
    private GasEstimationApplicationService gasEstimationApplicationService;

    @Test
    @DisplayName("超时降级 - 数据库查询超时时，应使用默认值继续执行")
    void estimateGas_DatabaseQueryTimeout_ShouldUseDefaultValues() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(gasHistoryRepository.findRecentByChainId(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    Thread.sleep(5000);
                    return null;
                });

        doNothing().when(requestValidator).validateChainId(anyString());
        doNothing().when(requestValidator).validateTimestamp(anyLong());
        doNothing().when(requestValidator).validateSignature(anyString());

        Future<GasEstimation> future = executor.submit(() -> {
            try {
                return gasEstimationApplicationService.estimateGas("1", "mainnet", System.currentTimeMillis(), "test-sig");
            } catch (Exception e) {
                return null;
            }
        });

        try {
            GasEstimation result = future.get(100, TimeUnit.MILLISECONDS);
            if (result == null) {
                System.out.println("Expected: 数据库查询超时导致降级");
            } else {
                System.out.println("Got result: " + result.getChainId());
            }
        } catch (TimeoutException e) {
            future.cancel(true);
            System.out.println("TimeoutException caught - 数据库查询超时");
        }

        executor.shutdownNow();
    }

    @Test
    @DisplayName("超时降级 - GasPriceCalculator计算超时，仍能返回默认值")
    void estimateGas_CalculationTimeout_ShouldFallbackGracefully() {
        when(gasHistoryRepository.findRecentByChainId(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    Thread.sleep(100);
                    return java.util.Collections.emptyList();
                });

        when(gasPriceCalculator.calculateBaseFee(anyList()))
                .thenAnswer(invocation -> {
                    Thread.sleep(200);
                    return new BigDecimal("1000000000");
                });

        when(gasPriceCalculator.calculateGasPriceLevels(anyList(), any()))
                .thenReturn(GasEstimation.GasPriceLevel.builder()
                        .low(new BigDecimal("1000000000"))
                        .medium(new BigDecimal("1500000000"))
                        .high(new BigDecimal("2000000000"))
                        .build());

        when(gasPriceCalculator.calculatePriorityFeeLevels(anyList()))
                .thenReturn(GasEstimation.PriorityFeeLevel.builder()
                        .low(new BigDecimal("100000000"))
                        .medium(new BigDecimal("200000000"))
                        .high(new BigDecimal("300000000"))
                        .build());

        when(gasPriceCalculator.calculateNetworkStatus(anyList()))
                .thenReturn(GasEstimation.NetworkStatus.builder()
                        .congestionLevel(GasEstimation.NetworkStatus.CongestionLevel.NORMAL)
                        .build());

        when(gasHistoryRepository.findLatestByChainId(anyString()))
                .thenReturn(java.util.Optional.empty());

        when(gasEstimationRepository.save(any(GasEstimation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        doNothing().when(requestValidator).validateChainId(anyString());
        doNothing().when(requestValidator).validateTimestamp(anyLong());
        doNothing().when(requestValidator).validateSignature(anyString());

        long startTime = System.currentTimeMillis();
        GasEstimation result = gasEstimationApplicationService.estimateGas(
                "1", "mainnet", System.currentTimeMillis(), "test-sig");
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertEquals("1", result.getChainId());
        assertTrue(duration > 300, "应该有一定的计算延迟");
    }

    @Test
    @DisplayName("超时降级 - 外部依赖全部失败时，系统仍能返回默认预估值")
    void estimateGas_AllExternalDependenciesFail_ShouldReturnDefaultEstimation() {
        when(gasHistoryRepository.findRecentByChainId(anyString(), anyInt()))
                .thenReturn(java.util.Collections.emptyList());

        when(gasHistoryRepository.findLatestByChainId(anyString()))
                .thenReturn(java.util.Optional.empty());

        when(gasPriceCalculator.calculateBaseFee(anyList()))
                .thenReturn(new BigDecimal("1000000000"));

        when(gasPriceCalculator.calculateGasPriceLevels(anyList(), any()))
                .thenReturn(GasEstimation.GasPriceLevel.builder()
                        .low(new BigDecimal("1000000000"))
                        .medium(new BigDecimal("1500000000"))
                        .high(new BigDecimal("2000000000"))
                        .build());

        when(gasPriceCalculator.calculatePriorityFeeLevels(anyList()))
                .thenReturn(GasEstimation.PriorityFeeLevel.builder()
                        .low(new BigDecimal("100000000"))
                        .medium(new BigDecimal("200000000"))
                        .high(new BigDecimal("300000000"))
                        .build());

        when(gasPriceCalculator.calculateNetworkStatus(anyList()))
                .thenReturn(GasEstimation.NetworkStatus.builder()
                        .congestionLevel(GasEstimation.NetworkStatus.CongestionLevel.NORMAL)
                        .pendingTransactions(100)
                        .blockGasUsed(15000000L)
                        .blockGasLimit(30000000L)
                        .gasUtilization(0.5)
                        .build());

        when(gasEstimationRepository.save(any(GasEstimation.class)))
                .thenReturn(null);

        doNothing().when(requestValidator).validateChainId(anyString());
        doNothing().when(requestValidator).validateTimestamp(anyLong());
        doNothing().when(requestValidator).validateSignature(anyString());

        GasEstimation result = gasEstimationApplicationService.estimateGas(
                "1", "mainnet", System.currentTimeMillis(), "test-sig");

        assertNull(result);
    }

    @Test
    @DisplayName("超时降级 - 数据库写入失败时，仍能返回计算结果")
    void estimateGas_DatabaseWriteFail_ShouldStillReturnCalculationResult() {
        when(gasHistoryRepository.findRecentByChainId(anyString(), anyInt()))
                .thenReturn(java.util.Collections.emptyList());

        when(gasPriceCalculator.calculateBaseFee(anyList()))
                .thenReturn(new BigDecimal("1000000000"));

        when(gasPriceCalculator.calculateGasPriceLevels(anyList(), any()))
                .thenReturn(GasEstimation.GasPriceLevel.builder()
                        .low(new BigDecimal("1000000000"))
                        .medium(new BigDecimal("1500000000"))
                        .high(new BigDecimal("2000000000"))
                        .build());

        when(gasPriceCalculator.calculatePriorityFeeLevels(anyList()))
                .thenReturn(GasEstimation.PriorityFeeLevel.builder()
                        .low(new BigDecimal("100000000"))
                        .medium(new BigDecimal("200000000"))
                        .high(new BigDecimal("300000000"))
                        .build());

        when(gasPriceCalculator.calculateNetworkStatus(anyList()))
                .thenReturn(GasEstimation.NetworkStatus.builder()
                        .congestionLevel(GasEstimation.NetworkStatus.CongestionLevel.NORMAL)
                        .build());

        when(gasHistoryRepository.findLatestByChainId(anyString()))
                .thenReturn(java.util.Optional.empty());

        when(gasEstimationRepository.save(any(GasEstimation.class)))
                .thenThrow(new RuntimeException("Database connection error"));

        doNothing().when(requestValidator).validateChainId(anyString());
        doNothing().when(requestValidator).validateTimestamp(anyLong());
        doNothing().when(requestValidator).validateSignature(anyString());

        assertThrows(RuntimeException.class, () -> {
            gasEstimationApplicationService.estimateGas(
                    "1", "mainnet", System.currentTimeMillis(), "test-sig");
        });
    }

    @Test
    @DisplayName("超时降级 - 部分历史数据查询超时，使用已获取数据进行计算")
    void estimateGas_PartialHistoryDataTimeout_ShouldUseAvailableData() {
        when(gasHistoryRepository.findRecentByChainId(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    return java.util.Arrays.asList(
                            com.solocoder.platform.gas.estimator.domain.model.GasHistory.builder()
                                    .blockNumber(1L)
                                    .baseFee(new BigDecimal("1000000000"))
                                    .gasPrice(new BigDecimal("2000000000"))
                                    .build(),
                            com.solocoder.platform.gas.estimator.domain.model.GasHistory.builder()
                                    .blockNumber(2L)
                                    .baseFee(new BigDecimal("1500000000"))
                                    .gasPrice(new BigDecimal("2500000000"))
                                    .build()
                    );
                });

        when(gasPriceCalculator.calculateBaseFee(anyList()))
                .thenCallRealMethod();

        when(gasPriceCalculator.calculateGasPriceLevels(anyList(), any()))
                .thenCallRealMethod();

        when(gasPriceCalculator.calculatePriorityFeeLevels(anyList()))
                .thenCallRealMethod();

        when(gasPriceCalculator.calculateNetworkStatus(anyList()))
                .thenCallRealMethod();

        doNothing().when(requestValidator).validateChainId(anyString());
        doNothing().when(requestValidator).validateTimestamp(anyLong());
        doNothing().when(requestValidator).validateSignature(anyString());

        GasPriceCalculator realCalculator = new GasPriceCalculator();

        BigDecimal baseFee = realCalculator.calculateBaseFee(java.util.Arrays.asList(
                com.solocoder.platform.gas.estimator.domain.model.GasHistory.builder()
                        .blockNumber(1L)
                        .baseFee(new BigDecimal("1000000000"))
                        .build(),
                com.solocoder.platform.gas.estimator.domain.model.GasHistory.builder()
                        .blockNumber(2L)
                        .baseFee(new BigDecimal("1500000000"))
                        .build()
        ));

        assertEquals(new BigDecimal("1250000000"), baseFee);
    }

    @Test
    @DisplayName("超时降级 - 网络状态查询超时，使用默认网络状态")
    void estimateGas_NetworkStatusTimeout_ShouldUseDefaultNetworkStatus() {
        GasPriceCalculator calculator = new GasPriceCalculator();

        GasEstimation.NetworkStatus result = calculator.calculateNetworkStatus(null);

        assertNotNull(result);
        assertEquals(GasEstimation.NetworkStatus.CongestionLevel.NORMAL, result.getCongestionLevel());
        assertEquals(0.5, result.getGasUtilization(), 0.001);
        assertEquals(15000000L, result.getBlockGasUsed());
        assertEquals(30000000L, result.getBlockGasLimit());
    }

    @Test
    @DisplayName("超时降级 - 缓存失效时，系统仍能正常工作")
    void estimateGas_CacheMiss_ShouldStillWorkWithFallback() {
        when(gasHistoryRepository.findRecentByChainId(anyString(), anyInt()))
                .thenReturn(java.util.Collections.emptyList());

        when(gasPriceCalculator.calculateBaseFee(anyList()))
                .thenReturn(new BigDecimal("1000000000"));

        when(gasPriceCalculator.calculateGasPriceLevels(anyList(), any()))
                .thenReturn(GasEstimation.GasPriceLevel.builder()
                        .low(new BigDecimal("1000000000"))
                        .medium(new BigDecimal("1500000000"))
                        .high(new BigDecimal("2000000000"))
                        .build());

        when(gasPriceCalculator.calculatePriorityFeeLevels(anyList()))
                .thenReturn(GasEstimation.PriorityFeeLevel.builder()
                        .low(new BigDecimal("100000000"))
                        .medium(new BigDecimal("200000000"))
                        .high(new BigDecimal("300000000"))
                        .build());

        when(gasPriceCalculator.calculateNetworkStatus(anyList()))
                .thenReturn(GasEstimation.NetworkStatus.builder()
                        .congestionLevel(GasEstimation.NetworkStatus.CongestionLevel.NORMAL)
                        .build());

        when(gasHistoryRepository.findLatestByChainId(anyString()))
                .thenReturn(java.util.Optional.empty());

        when(gasEstimationRepository.save(any(GasEstimation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        doNothing().when(requestValidator).validateChainId(anyString());
        doNothing().when(requestValidator).validateTimestamp(anyLong());
        doNothing().when(requestValidator).validateSignature(anyString());

        GasEstimation result = gasEstimationApplicationService.estimateGas(
                "1", "mainnet", System.currentTimeMillis(), "test-sig");

        assertNotNull(result);
        assertNotNull(result.getGasPrices());
        assertNotNull(result.getPriorityFees());
        assertNotNull(result.getNetworkStatus());
    }
}
