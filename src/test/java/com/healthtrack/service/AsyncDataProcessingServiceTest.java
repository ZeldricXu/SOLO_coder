package com.healthtrack.service;

import com.healthtrack.dto.HealthDataReportRequest;
import com.healthtrack.dto.HealthDataReportResponse;
import com.healthtrack.entity.HealthData;
import com.healthtrack.repository.HealthDataRepository;
import com.healthtrack.repository.HealthHistoryRepository;
import com.healthtrack.repository.HealthIndicatorRepository;
import com.healthtrack.testbuilder.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("异步数据采集单元测试")
class AsyncDataProcessingServiceTest {

    @Mock
    private HealthDataRepository healthDataRepository;

    @Mock
    private HealthIndicatorRepository healthIndicatorRepository;

    @Mock
    private IndicatorTrackingService indicatorTrackingService;

    @Mock
    private GoalManagementService goalManagementService;

    @Mock
    private AdvicePushService advicePushService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @Mock
    private HealthHistoryRepository healthHistoryRepository;

    @InjectMocks
    private AsyncDataProcessingService asyncDataProcessingService;

    @InjectMocks
    private DataCollectionService dataCollectionService;

    @BeforeEach
    void setUp() {
        asyncDataProcessingService.resetCounters();
    }

    @Nested
    @DisplayName("异步处理基本功能测试")
    class AsyncBasicTests {

        @Test
        @DisplayName("健康数据异步存储 - 成功")
        void testAsyncDataSave() throws Exception {
            HealthData data = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(data);
            
            CompletableFuture<Boolean> future = asyncDataProcessingService.saveDataOnlyAsync(data);
            
            Boolean result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result);
            verify(healthDataRepository, times(1)).save(any(HealthData.class));
        }

        @Test
        @DisplayName("异步存储失败 - 异常传播")
        void testAsyncDataSaveFailure() {
            HealthData data = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenThrow(new RuntimeException("存储失败"));
            
            CompletableFuture<Boolean> future = asyncDataProcessingService.saveDataOnlyAsync(data);
            
            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof RuntimeException);
            assertEquals("存储失败", exception.getCause().getMessage());
        }

        @Test
        @DisplayName("完整异步处理流程 - 成功")
        void testFullAsyncProcessing() throws Exception {
            HealthData data = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(data);
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("normal");
            
            CompletableFuture<HealthData> future = asyncDataProcessingService.processHealthDataAsync(data);
            
            HealthData result = future.get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            verify(healthDataRepository, times(1)).save(any(HealthData.class));
            verify(indicatorTrackingService, times(1)).updateIndicator(
                    eq(data.getUserId()), eq(data.getDataType()), eq(data.getDataValue()));
            verify(goalManagementService, times(1)).checkGoals(
                    eq(data.getUserId()), eq(data.getDataType()), eq(data.getDataValue()));
            verify(advicePushService, times(1)).generateAdviceIfNeeded(
                    eq(data.getUserId()), eq(data.getDataType()));
            verify(statisticsService, times(1)).updateStatistics(
                    eq(data.getUserId()), eq(data.getDataType()), eq(true));
        }

        @Test
        @DisplayName("处理计数器 - 正常工作")
        void testProcessingCounters() throws Exception {
            HealthData data = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(data);
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("normal");
            
            assertEquals(0, asyncDataProcessingService.getProcessedCount());
            assertEquals(0, asyncDataProcessingService.getFailedCount());
            
            asyncDataProcessingService.processHealthDataAsync(data).get(5, TimeUnit.SECONDS);
            asyncDataProcessingService.processHealthDataAsync(data).get(5, TimeUnit.SECONDS);
            
            assertEquals(2, asyncDataProcessingService.getProcessedCount());
            assertEquals(0, asyncDataProcessingService.getFailedCount());
        }

        @Test
        @DisplayName("失败计数器 - 正常工作")
        void testFailureCounters() {
            HealthData data = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenThrow(new RuntimeException("存储失败"));
            
            CompletableFuture<HealthData> future = asyncDataProcessingService.processHealthDataAsync(data);
            
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 预期异常
            }
            
            assertEquals(0, asyncDataProcessingService.getProcessedCount());
            assertEquals(1, asyncDataProcessingService.getFailedCount());
        }

        @Test
        @DisplayName("计数器重置 - 正常工作")
        void testCounterReset() throws Exception {
            HealthData data = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(data);
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("normal");
            
            asyncDataProcessingService.processHealthDataAsync(data).get(5, TimeUnit.SECONDS);
            assertEquals(1, asyncDataProcessingService.getProcessedCount());
            
            asyncDataProcessingService.resetCounters();
            assertEquals(0, asyncDataProcessingService.getProcessedCount());
            assertEquals(0, asyncDataProcessingService.getFailedCount());
        }
    }

    @Nested
    @DisplayName("采集异步化机制测试")
    class AsyncCollectionTests {

        @Test
        @DisplayName("异步模式 - 立即返回响应")
        void testAsyncModeImmediateResponse() {
            dataCollectionService.setUseAsyncProcessing(true);
            HealthDataReportRequest request = TestDataBuilder.buildNormalHeartRateRequest();
            
            long startTime = System.currentTimeMillis();
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            long duration = System.currentTimeMillis() - startTime;
            
            assertNotNull(response);
            assertNotNull(response.getDataId());
            assertEquals("processing", response.getIndicatorStatus());
            assertTrue(duration < 100, "异步处理应该立即返回，耗时应小于100ms");
        }

        @Test
        @DisplayName("异步模式开关 - 可切换")
        void testAsyncModeToggle() {
            assertTrue(dataCollectionService.isUseAsyncProcessing());
            
            dataCollectionService.setUseAsyncProcessing(false);
            assertFalse(dataCollectionService.isUseAsyncProcessing());
            
            dataCollectionService.setUseAsyncProcessing(true);
            assertTrue(dataCollectionService.isUseAsyncProcessing());
        }

        @Test
        @DisplayName("同步模式 - 完整执行流程")
        void testSyncModeFullFlow() {
            dataCollectionService.setUseAsyncProcessing(false);
            HealthDataReportRequest request = TestDataBuilder.buildNormalHeartRateRequest();
            HealthData mockData = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(mockData);
            
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            
            assertNotNull(response);
            assertEquals("normal", response.getIndicatorStatus());
            verify(healthDataRepository, times(1)).save(any(HealthData.class));
            verify(historyService, times(1)).recordHistory(anyString(), anyString(), anyString(), any(), anyDouble(), anyString());
        }

        @Test
        @DisplayName("CompletableFuture异步方法 - 成功处理")
        void testCompletableFutureAsyncMethod() throws Exception {
            dataCollectionService.setUseAsyncProcessing(true);
            HealthDataReportRequest request = TestDataBuilder.buildNormalHeartRateRequest();
            HealthData mockData = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(mockData);
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("normal");
            
            CompletableFuture<HealthDataReportResponse> future = dataCollectionService.reportHealthDataAsync(request);
            
            HealthDataReportResponse response = future.get(10, TimeUnit.SECONDS);
            
            assertNotNull(response);
            assertNotNull(response.getDataId());
        }

        @Test
        @DisplayName("CompletableFuture异步方法 - 异常处理")
        void testCompletableFutureExceptionHandling() throws Exception {
            dataCollectionService.setUseAsyncProcessing(true);
            HealthDataReportRequest request = TestDataBuilder.buildNormalHeartRateRequest();
            
            when(healthDataRepository.save(any(HealthData.class))).thenThrow(new RuntimeException("处理失败"));
            
            CompletableFuture<HealthDataReportResponse> future = dataCollectionService.reportHealthDataAsync(request);
            
            HealthDataReportResponse response = future.get(10, TimeUnit.SECONDS);
            
            assertNotNull(response);
            assertEquals("failed", response.getIndicatorStatus());
        }
    }

    @Nested
    @DisplayName("高并发异步处理测试")
    class HighConcurrencyAsyncTests {

        @Test
        @DisplayName("100并发异步请求 - 全部成功")
        void test100ConcurrentAsyncRequests() throws InterruptedException {
            int requestCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(20);
            CountDownLatch latch = new CountDownLatch(requestCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            HealthData mockData = TestDataBuilder.buildNormalHealthData();
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(mockData);
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("normal");
            
            for (int i = 0; i < requestCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        HealthData data = TestDataBuilder.buildHealthData(
                                "user_" + (index % 10),
                                "heart_rate",
                                70.0 + index,
                                "good"
                        );
                        asyncDataProcessingService.processHealthDataAsync(data).get(5, TimeUnit.SECONDS);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // 记录异常但不影响其他请求
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            
            assertTrue(completed, "所有任务应该在30秒内完成");
            assertEquals(requestCount, successCount.get());
            verify(healthDataRepository, times(requestCount)).save(any(HealthData.class));
        }

        @Test
        @DisplayName("并发处理 - 线程安全")
        void testConcurrentThreadSafety() throws InterruptedException {
            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            
            HealthData mockData = TestDataBuilder.buildNormalHealthData();
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(mockData);
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("normal");
            
            List<Future<?>> futures = new ArrayList<>();
            
            for (int i = 0; i < threadCount; i++) {
                Future<?> future = executor.submit(() -> {
                    try {
                        startLatch.await();
                        HealthData data = TestDataBuilder.buildNormalHealthData();
                        asyncDataProcessingService.processHealthDataAsync(data).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // 记录异常
                    } finally {
                        endLatch.countDown();
                    }
                });
                futures.add(future);
            }
            
            startLatch.countDown();
            boolean completed = endLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            
            assertTrue(completed);
            assertEquals(threadCount, asyncDataProcessingService.getProcessedCount());
        }

        @Test
        @DisplayName("混合成功失败场景 - 计数正确")
        void testMixedSuccessFailure() throws InterruptedException {
            int successCount = 80;
            int failureCount = 20;
            int totalCount = successCount + failureCount;
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(totalCount);
            
            AtomicInteger callCount = new AtomicInteger(0);
            when(healthDataRepository.save(any(HealthData.class))).thenAnswer(invocation -> {
                int count = callCount.incrementAndGet();
                if (count <= successCount) {
                    return invocation.getArgument(0);
                } else {
                    throw new RuntimeException("模拟存储失败");
                }
            });
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("normal");
            
            for (int i = 0; i < totalCount; i++) {
                executor.submit(() -> {
                    try {
                        HealthData data = TestDataBuilder.buildNormalHealthData();
                        asyncDataProcessingService.processHealthDataAsync(data).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // 预期异常
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            
            assertEquals(successCount, asyncDataProcessingService.getProcessedCount());
            assertEquals(failureCount, asyncDataProcessingService.getFailedCount());
        }
    }

    @Nested
    @DisplayName("异常数据异步处理测试")
    class AbnormalDataAsyncTests {

        @Test
        @DisplayName("异常数据异步处理 - 正常流程")
        void testAbnormalDataAsyncProcessing() throws Exception {
            HealthData abnormalData = TestDataBuilder.buildAbnormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(abnormalData);
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("abnormal");
            
            CompletableFuture<HealthData> future = asyncDataProcessingService.processHealthDataAsync(abnormalData);
            
            HealthData result = future.get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals("abnormal", result.getQuality());
        }

        @Test
        @DisplayName("不同数据类型异步处理")
        void testDifferentDataTypesAsync() throws Exception {
            String[] dataTypes = {"heart_rate", "weight", "steps", "sleep_hours", "temperature"};
            
            when(healthDataRepository.save(any(HealthData.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("normal");
            
            for (String dataType : dataTypes) {
                HealthData data = TestDataBuilder.buildHealthData("user_001", dataType, 70.0, "good");
                CompletableFuture<HealthData> future = asyncDataProcessingService.processHealthDataAsync(data);
                HealthData result = future.get(5, TimeUnit.SECONDS);
                assertNotNull(result);
                assertEquals(dataType, result.getDataType());
            }
        }
    }

    @Nested
    @DisplayName("历史记录异步测试")
    class HistoryAsyncTests {

        @Test
        @DisplayName("异步处理时记录历史")
        void testHistoryRecordingOnAsyncProcessing() throws Exception {
            HealthData data = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(data);
            when(indicatorTrackingService.updateIndicator(anyString(), anyString(), anyDouble())).thenReturn("normal");
            
            asyncDataProcessingService.processHealthDataAsync(data).get(5, TimeUnit.SECONDS);
            
            verify(historyService, times(1)).recordHistory(
                    eq(data.getUserId()),
                    eq(data.getDataType()),
                    eq("DATA_PROCESSED"),
                    any(),
                    eq(data.getDataValue()),
                    anyString()
            );
        }
    }
}
