package com.healthtrack.service;

import com.healthtrack.entity.HealthData;
import com.healthtrack.entity.HealthIndicator;
import com.healthtrack.repository.HealthDataRepository;
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

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("异步指标分析单元测试")
class AsyncIndicatorAnalysisServiceTest {

    @Mock
    private HealthIndicatorRepository healthIndicatorRepository;

    @Mock
    private HealthDataRepository healthDataRepository;

    @Mock
    private ReminderService reminderService;

    @InjectMocks
    private AsyncIndicatorAnalysisService asyncIndicatorAnalysisService;

    @BeforeEach
    void setUp() {
        asyncIndicatorAnalysisService.resetCounters();
    }

    @Nested
    @DisplayName("异步分析基本功能测试")
    class AsyncAnalysisBasicTests {

        @Test
        @DisplayName("异步指标分析 - 成功返回指标")
        void testAsyncAnalysisSuccess() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator existingIndicator = TestDataBuilder.buildHeartRateIndicator(userId);
            List<HealthData> recentData = TestDataBuilder.buildRecentHealthDataList(userId, "heart_rate", 7);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(existingIndicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(recentData);
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            CompletableFuture<HealthIndicator> future = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 80.0);
            
            HealthIndicator result = future.get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals(80.0, result.getCurrentValue());
            verify(healthIndicatorRepository, times(1)).save(any(HealthIndicator.class));
            assertEquals(1, asyncIndicatorAnalysisService.getAnalysisCount());
            assertEquals(0, asyncIndicatorAnalysisService.getFailedCount());
        }

        @Test
        @DisplayName("新指标首次分析 - 创建新记录")
        void testNewIndicatorCreation() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            List<HealthData> recentData = TestDataBuilder.buildRecentHealthDataList(userId, "weight", 5);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "weight"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("weight"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(recentData);
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            CompletableFuture<HealthIndicator> future = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "weight", 65.0);
            
            HealthIndicator result = future.get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertTrue(result.getIndicatorId().startsWith("indicator_"));
            assertEquals(65.0, result.getCurrentValue());
            assertEquals(65.0, result.getAverageValue());
            assertEquals(65.0, result.getMaxValue());
            assertEquals(65.0, result.getMinValue());
        }

        @Test
        @DisplayName("指标分析 - 立即返回Future不阻塞")
        void testAsyncAnalysisNonBlocking() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator existingIndicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(existingIndicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> {
                Thread.sleep(100);
                return invocation.getArgument(0);
            });
            
            long startTime = System.currentTimeMillis();
            CompletableFuture<HealthIndicator> future = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0);
            long submitTime = System.currentTimeMillis() - startTime;
            
            assertTrue(submitTime < 50, "异步提交应该立即返回Future，不阻塞主线程");
            assertNotNull(future);
        }

        @Test
        @DisplayName("处理计数器 - 正常工作")
        void testAnalysisCounters() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            assertEquals(0, asyncIndicatorAnalysisService.getAnalysisCount());
            assertEquals(0, asyncIndicatorAnalysisService.getFailedCount());
            assertEquals(0, asyncIndicatorAnalysisService.getRetryCount());
            
            asyncIndicatorAnalysisService.analyzeIndicatorAsync(userId, "heart_rate", 75.0).get(10, TimeUnit.SECONDS);
            asyncIndicatorAnalysisService.analyzeIndicatorAsync(userId, "heart_rate", 80.0).get(10, TimeUnit.SECONDS);
            
            assertEquals(2, asyncIndicatorAnalysisService.getAnalysisCount());
            assertEquals(0, asyncIndicatorAnalysisService.getFailedCount());
        }

        @Test
        @DisplayName("计数器重置 - 正常工作")
        void testCounterReset() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            asyncIndicatorAnalysisService.analyzeIndicatorAsync(userId, "heart_rate", 75.0).get(10, TimeUnit.SECONDS);
            assertEquals(1, asyncIndicatorAnalysisService.getAnalysisCount());
            
            asyncIndicatorAnalysisService.resetCounters();
            assertEquals(0, asyncIndicatorAnalysisService.getAnalysisCount());
            assertEquals(0, asyncIndicatorAnalysisService.getFailedCount());
            assertEquals(0, asyncIndicatorAnalysisService.getRetryCount());
        }
    }

    @Nested
    @DisplayName("指标计算逻辑测试")
    class IndicatorCalculationTests {

        @Test
        @DisplayName("平均值计算 - 使用最近7天数据")
        void testAverageValueCalculation() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            List<HealthData> recentData = new ArrayList<>();
            
            for (int i = 0; i < 7; i++) {
                HealthData data = TestDataBuilder.buildHealthData(userId, "heart_rate", 70.0 + i, "good");
                data.setCollectedAt(LocalDateTime.now().minusDays(i));
                recentData.add(data);
            }
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(recentData);
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0).get(10, TimeUnit.SECONDS);
            
            assertNotNull(result.getAverageValue());
            assertTrue(result.getAverageValue() >= 70 && result.getAverageValue() <= 80);
        }

        @Test
        @DisplayName("最大值最小值计算")
        void testMaxMinValueCalculation() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            List<HealthData> recentData = Arrays.asList(
                    TestDataBuilder.buildHealthData(userId, "heart_rate", 60.0, "good"),
                    TestDataBuilder.buildHealthData(userId, "heart_rate", 80.0, "good"),
                    TestDataBuilder.buildHealthData(userId, "heart_rate", 70.0, "good")
            );
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(recentData);
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0).get(10, TimeUnit.SECONDS);
            
            assertEquals(80.0, result.getMaxValue());
            assertEquals(60.0, result.getMinValue());
        }

        @Test
        @DisplayName("无历史数据时 - 使用当前值作为统计")
        void testNoHistoricalData() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0).get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals(75.0, result.getCurrentValue());
        }

        @Test
        @DisplayName("趋势分析 - 数值上升")
        void testTrendAnalysisRising() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            indicator.setCurrentValue(70.0);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 90.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("rising", result.getTrend());
        }

        @Test
        @DisplayName("趋势分析 - 数值下降")
        void testTrendAnalysisFalling() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            indicator.setCurrentValue(90.0);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 70.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("falling", result.getTrend());
        }

        @Test
        @DisplayName("趋势分析 - 数值稳定（小幅度变化）")
        void testTrendAnalysisStable() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            indicator.setCurrentValue(75.0);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 76.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("stable", result.getTrend());
        }
    }

    @Nested
    @DisplayName("指标状态判断测试")
    class IndicatorStatusTests {

        @Test
        @DisplayName("正常心率 - 状态为normal")
        void testNormalHeartRateStatus() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("normal", result.getStatus());
            verify(reminderService, never()).checkAndTriggerAbnormalityReminder(anyString(), anyString(), anyDouble());
        }

        @Test
        @DisplayName("异常心率 - 状态为abnormal并触发提醒")
        void testAbnormalHeartRateStatus() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 150.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("abnormal", result.getStatus());
            verify(reminderService, times(1)).checkAndTriggerAbnormalityReminder(
                    eq(userId), eq("heart_rate"), eq(150.0));
        }

        @Test
        @DisplayName("正常体重范围")
        void testNormalWeightStatus() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "weight"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("weight"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "weight", 65.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("normal", result.getStatus());
        }

        @Test
        @DisplayName("异常体重范围")
        void testAbnormalWeightStatus() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "weight"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("weight"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "weight", 350.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("abnormal", result.getStatus());
        }

        @Test
        @DisplayName("正常血压范围")
        void testNormalBloodPressureStatus() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "blood_pressure_systolic"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("blood_pressure_systolic"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "blood_pressure_systolic", 120.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("normal", result.getStatus());
        }

        @Test
        @DisplayName("异常血压范围")
        void testAbnormalBloodPressureStatus() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "blood_pressure_systolic"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("blood_pressure_systolic"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "blood_pressure_systolic", 180.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("abnormal", result.getStatus());
        }

        @Test
        @DisplayName("正常体温范围")
        void testNormalTemperatureStatus() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "temperature"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("temperature"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "temperature", 36.8).get(10, TimeUnit.SECONDS);
            
            assertEquals("normal", result.getStatus());
        }

        @Test
        @DisplayName("异常体温范围")
        void testAbnormalTemperatureStatus() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "temperature"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("temperature"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "temperature", 39.0).get(10, TimeUnit.SECONDS);
            
            assertEquals("abnormal", result.getStatus());
        }
    }

    @Nested
    @DisplayName("重试机制测试")
    class RetryMechanismTests {

        @Test
        @DisplayName("首次失败后重试 - 第二次成功")
        void testRetryOnFirstFailure() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            AtomicInteger attemptCount = new AtomicInteger(0);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> {
                int attempt = attemptCount.incrementAndGet();
                if (attempt < 2) {
                    throw new RuntimeException("模拟数据库连接失败");
                }
                return invocation.getArgument(0);
            });
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0).get(15, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals(1, asyncIndicatorAnalysisService.getAnalysisCount());
            assertEquals(0, asyncIndicatorAnalysisService.getFailedCount());
            assertEquals(1, asyncIndicatorAnalysisService.getRetryCount());
        }

        @Test
        @DisplayName("多次失败后最终成功")
        void testMultipleRetriesSuccess() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            AtomicInteger attemptCount = new AtomicInteger(0);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> {
                int attempt = attemptCount.incrementAndGet();
                if (attempt < 3) {
                    throw new RuntimeException("模拟数据库连接失败，尝试: " + attempt);
                }
                return invocation.getArgument(0);
            });
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0).get(20, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals(2, asyncIndicatorAnalysisService.getRetryCount());
        }

        @Test
        @DisplayName("超过最大重试次数 - 标记失败")
        void testExceedMaxRetries() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class)))
                    .thenThrow(new RuntimeException("持续数据库连接失败"));
            
            CompletableFuture<HealthIndicator> future = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0);
            
            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> future.get(30, TimeUnit.SECONDS));
            
            assertTrue(exception.getCause() instanceof RuntimeException);
            assertEquals(0, asyncIndicatorAnalysisService.getAnalysisCount());
            assertEquals(1, asyncIndicatorAnalysisService.getFailedCount());
            assertEquals(2, asyncIndicatorAnalysisService.getRetryCount());
        }

        @Test
        @DisplayName("首次成功 - 不触发重试")
        void testNoRetryOnFirstSuccess() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0).get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals(0, asyncIndicatorAnalysisService.getRetryCount());
        }
    }

    @Nested
    @DisplayName("高并发异步分析测试")
    class HighConcurrencyAnalysisTests {

        @Test
        @DisplayName("50并发分析请求 - 全部成功")
        void test50ConcurrentAnalysisRequests() throws InterruptedException {
            int requestCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(requestCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    anyString(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            for (int i = 0; i < requestCount; i++) {
                final String userId = "user_" + (i % 10);
                final double value = 60 + (i % 50);
                executor.submit(() -> {
                    try {
                        CompletableFuture<HealthIndicator> future = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                                userId, "heart_rate", value);
                        HealthIndicator result = future.get(10, TimeUnit.SECONDS);
                        if (result != null) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // 记录异常
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            
            assertTrue(completed);
            assertEquals(requestCount, successCount.get());
            assertEquals(requestCount, asyncIndicatorAnalysisService.getAnalysisCount());
        }

        @Test
        @DisplayName("并发分析 - 线程安全计数器")
        void testConcurrentThreadSafeCounters() throws InterruptedException {
            int threadCount = 30;
            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    anyString(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            for (int i = 0; i < threadCount; i++) {
                final String userId = "user_" + i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        CompletableFuture<HealthIndicator> future = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                                userId, "heart_rate", 75.0);
                        future.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // 记录异常
                    } finally {
                        endLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown();
            boolean completed = endLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            
            assertTrue(completed);
            assertEquals(threadCount, asyncIndicatorAnalysisService.getAnalysisCount());
            assertEquals(0, asyncIndicatorAnalysisService.getFailedCount());
        }
    }

    @Nested
    @DisplayName("不同数据类型分析测试")
    class DifferentDataTypeTests {

        @Test
        @DisplayName("心率指标分析")
        void testHeartRateAnalysis() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("heart_rate"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "heart_rate", 75.0).get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals("heart_rate", result.getIndicatorType());
            assertEquals(75.0, result.getTargetValue());
        }

        @Test
        @DisplayName("体重指标分析")
        void testWeightAnalysis() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "weight"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("weight"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "weight", 65.0).get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals("weight", result.getIndicatorType());
            assertEquals(65.0, result.getTargetValue());
        }

        @Test
        @DisplayName("步数指标分析")
        void testStepsAnalysis() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "steps"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("steps"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "steps", 8000.0).get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals("steps", result.getIndicatorType());
            assertEquals(8000.0, result.getTargetValue());
        }

        @Test
        @DisplayName("睡眠指标分析")
        void testSleepAnalysis() throws Exception {
            String userId = TestDataBuilder.getDefaultUserId();
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "sleep_hours"))
                    .thenReturn(Optional.empty());
            when(healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                    eq(userId), eq("sleep_hours"), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthIndicatorRepository.save(any(HealthIndicator.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthIndicator result = asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                    userId, "sleep_hours", 7.5).get(10, TimeUnit.SECONDS);
            
            assertNotNull(result);
            assertEquals("sleep_hours", result.getIndicatorType());
            assertEquals(8.0, result.getTargetValue());
        }
    }
}
