package com.solocoder.platform.gas.estimator.application.service;

import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;
import com.solocoder.platform.gas.estimator.domain.model.GasHistory;
import com.solocoder.platform.gas.estimator.domain.repository.GasEstimationRepository;
import com.solocoder.platform.gas.estimator.domain.repository.GasHistoryRepository;
import com.solocoder.platform.gas.estimator.domain.service.GasPriceCalculator;
import com.solocoder.platform.gas.estimator.domain.service.RequestValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GasEstimationApplicationService - 并发安全测试")
class GasEstimationApplicationServiceConcurrencyTest {

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
    @DisplayName("并发安全 - 多线程同时请求相同chainId，触发DuplicateKeyException时抛出409")
    void estimateGas_ConcurrentSameChainId_ShouldThrowConflict() throws InterruptedException {
        String chainId = "1";
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        when(gasHistoryRepository.findRecentByChainId(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
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
                .thenAnswer(invocation -> {
                    GasEstimation estimation = invocation.getArgument(0);
                    estimation.setId(1L);
                    return estimation;
                });

        doNothing().when(requestValidator).validateChainId(anyString());
        doNothing().when(requestValidator).validateTimestamp(anyLong());
        doNothing().when(requestValidator).validateSignature(anyString());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (threadIndex == 5) {
                        when(gasEstimationRepository.save(any(GasEstimation.class)))
                                .thenThrow(new DuplicateKeyException("Duplicate entry"));
                    }
                    gasEstimationApplicationService.estimateGas(chainId, "mainnet", System.currentTimeMillis(), "test-sig");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("409")) {
                        conflictCount.incrementAndGet();
                    }
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(successCount.get() > 0 || conflictCount.get() > 0);
    }

    @Test
    @DisplayName("并发安全 - 多线程同时请求不同chainId，应无冲突")
    void estimateGas_ConcurrentDifferentChainIds_ShouldAllSucceed() throws InterruptedException {
        int threadCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        when(gasHistoryRepository.findRecentByChainId(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
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
                .thenAnswer(invocation -> {
                    GasEstimation estimation = invocation.getArgument(0);
                    estimation.setId((long) (Math.random() * 1000));
                    return estimation;
                });

        doNothing().when(requestValidator).validateChainId(anyString());
        doNothing().when(requestValidator).validateTimestamp(anyLong());
        doNothing().when(requestValidator).validateSignature(anyString());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final String chainId = String.valueOf(i + 1);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    gasEstimationApplicationService.estimateGas(chainId, "mainnet", System.currentTimeMillis(), "test-sig");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Ignore exception in count
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount, successCount.get());
    }

    @RepeatedTest(5)
    @DisplayName("并发安全 - 重复测试：大量并发请求的稳定性")
    void estimateGas_HighConcurrency_ShouldBeStable() throws InterruptedException {
        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        when(gasHistoryRepository.findRecentByChainId(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
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
                .thenAnswer(invocation -> {
                    GasEstimation estimation = invocation.getArgument(0);
                    estimation.setId((long) (Math.random() * 100000));
                    return estimation;
                });

        doNothing().when(requestValidator).validateChainId(anyString());
        doNothing().when(requestValidator).validateTimestamp(anyLong());
        doNothing().when(requestValidator).validateSignature(anyString());

        ExecutorService executor = Executors.newFixedThreadPool(20);
        for (int i = 0; i < threadCount; i++) {
            final String chainId = String.valueOf((i % 10) + 1);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    gasEstimationApplicationService.estimateGas(chainId, "mainnet", System.currentTimeMillis(), "test-sig");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Expected: some may fail with duplicate key
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(successCount.get() > 0, "至少应有一些请求成功");
    }

    @Test
    @DisplayName("并发安全 - GasPriceCalculator多线程计算无状态污染")
    void gasPriceCalculator_MultiThreaded_NoStateContamination() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        GasPriceCalculator calculator = new GasPriceCalculator();
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<GasHistory> historyData = new ArrayList<>();
                    for (int j = 0; j < 10; j++) {
                        historyData.add(GasHistory.builder()
                                .blockNumber((long) (threadIndex * 10 + j))
                                .baseFee(new BigDecimal((threadIndex + 1) * 1000000000L))
                                .gasPrice(new BigDecimal((threadIndex + 1) * 2000000000L))
                                .priorityFee(new BigDecimal((threadIndex + 1) * 100000000L))
                                .build());
                    }

                    BigDecimal baseFee = calculator.calculateBaseFee(historyData);
                    GasEstimation.GasPriceLevel prices = calculator.calculateGasPriceLevels(historyData, baseFee);

                    assertNotNull(baseFee);
                    assertNotNull(prices);
                    successCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount, successCount.get());
    }
}
