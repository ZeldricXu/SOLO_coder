package com.mobilestore.service;

import com.mobilestore.entity.Statistics;
import com.mobilestore.repository.StatisticsRepository;
import com.mobilestore.test.BaseServiceTest;
import com.mobilestore.test.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("异步统计服务测试")
class AsyncStatisticsServiceTest extends BaseServiceTest {

    @Mock
    private StatisticsRepository statisticsRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private AsyncStatisticsService asyncStatisticsService;

    @Nested
    @DisplayName("统计计算测试")
    class StatisticsCalculationTests {

        @Test
        @DisplayName("计算摘要统计数据")
        void calculateSummary_shouldComputeCorrectly() {
            LocalDate today = LocalDate.now();
            LocalDate weekAgo = today.minusDays(6);
            List<Statistics> statsData = Arrays.asList(
                TestDataBuilder.buildStatistics("app_001", weekAgo, 100L, 50L, 4.0),
                TestDataBuilder.buildStatistics("app_001", weekAgo.plusDays(1), 200L, 80L, 4.2),
                TestDataBuilder.buildStatistics("app_001", weekAgo.plusDays(2), 300L, 120L, 4.5),
                TestDataBuilder.buildStatistics("app_001", weekAgo.plusDays(3), 150L, 90L, 4.0),
                TestDataBuilder.buildStatistics("app_001", weekAgo.plusDays(4), 250L, 110L, 4.3),
                TestDataBuilder.buildStatistics("app_001", weekAgo.plusDays(5), 180L, 85L, 4.1),
                TestDataBuilder.buildStatistics("app_001", today, 220L, 100L, 4.4)
            );

            when(statisticsRepository.findByAppIdAndStatDateBetween("app_001", weekAgo, today)).thenReturn(statsData);

            Map<String, Object> result = asyncStatisticsService.calculateSummary("app_001");

            assertNotNull(result);
            assertTrue(result.containsKey("totalDownloads"));
            assertTrue(result.containsKey("avgDailyDownloads"));
            assertTrue(result.containsKey("activeUsers"));
            assertTrue(result.containsKey("avgRating"));
            assertTrue(result.containsKey("feedbackCount"));

            long totalDownloads = (long) result.get("totalDownloads");
            assertEquals(1400L, totalDownloads);
        }

        @Test
        @DisplayName("计算图表统计数据")
        void calculateChartData_shouldReturnSeries() {
            LocalDate today = LocalDate.now();
            LocalDate weekAgo = today.minusDays(6);
            List<Statistics> statsData = Arrays.asList(
                TestDataBuilder.buildStatistics("app_001", weekAgo, 100L, 50L, 4.0),
                TestDataBuilder.buildStatistics("app_001", weekAgo.plusDays(1), 200L, 80L, 4.2)
            );

            when(statisticsRepository.findByAppIdAndStatDateBetween("app_001", weekAgo, today)).thenReturn(statsData);

            Map<String, Object> result = asyncStatisticsService.calculateChartData("app_001");

            assertNotNull(result);
            assertTrue(result.containsKey("dates"));
            assertTrue(result.containsKey("downloads"));
            assertTrue(result.containsKey("activeUsers"));
            assertTrue(result.containsKey("ratings"));
        }

        @Test
        @DisplayName("无数据时应返回零值统计")
        void calculateSummary_noData_shouldReturnZeros() {
            LocalDate today = LocalDate.now();
            LocalDate weekAgo = today.minusDays(6);

            when(statisticsRepository.findByAppIdAndStatDateBetween("app_001", weekAgo, today))
                .thenReturn(Arrays.asList());

            Map<String, Object> result = asyncStatisticsService.calculateSummary("app_001");

            assertNotNull(result);
            assertEquals(0L, result.get("totalDownloads"));
            assertEquals(0.0, result.get("avgRating"));
            assertTrue(result.containsKey("lastUpdated"));
        }
    }

    @Nested
    @DisplayName("缓存机制测试")
    class CacheMechanismTests {

        @Test
        @DisplayName("获取缓存摘要应返回缓存数据")
        void getCachedSummary_shouldReturnCachedData() {
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("totalDownloads", 1000L);
            cachedData.put("avgRating", 4.5);

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("stats:summary:app_001")).thenReturn(cachedData);

            Map<String, Object> result = asyncStatisticsService.getCachedSummary("app_001");

            assertNotNull(result);
            assertEquals(1000L, result.get("totalDownloads"));
            assertEquals(4.5, result.get("avgRating"));
        }

        @Test
        @DisplayName("缓存不存在时应返回null")
        void getCachedSummary_noCache_shouldReturnNull() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("stats:summary:app_001")).thenReturn(null);

            Map<String, Object> result = asyncStatisticsService.getCachedSummary("app_001");

            assertNull(result);
        }

        @Test
        @DisplayName("缓存摘要数据应设置过期时间")
        void cacheSummary_shouldSetExpiration() {
            Map<String, Object> summaryData = new HashMap<>();
            summaryData.put("totalDownloads", 1000L);

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            asyncStatisticsService.cacheSummary("app_001", summaryData);

            ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<TimeUnit> unitCaptor = ArgumentCaptor.forClass(TimeUnit.class);
            verify(valueOperations, times(1)).set(
                eq("stats:summary:app_001"),
                eq(summaryData),
                ttlCaptor.capture(),
                unitCaptor.capture()
            );

            assertEquals(30L, ttlCaptor.getValue());
            assertEquals(TimeUnit.MINUTES, unitCaptor.getValue());
        }

        @Test
        @DisplayName("缓存图表数据应设置过期时间")
        void cacheChartData_shouldSetExpiration() {
            Map<String, Object> chartData = new HashMap<>();
            chartData.put("dates", Arrays.asList("2024-01-01"));

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            asyncStatisticsService.cacheChartData("app_001", chartData);

            ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<TimeUnit> unitCaptor = ArgumentCaptor.forClass(TimeUnit.class);
            verify(valueOperations, times(1)).set(
                eq("stats:chart:app_001"),
                eq(chartData),
                ttlCaptor.capture(),
                unitCaptor.capture()
            );

            assertEquals(15L, ttlCaptor.getValue());
            assertEquals(TimeUnit.MINUTES, unitCaptor.getValue());
        }

        @Test
        @DisplayName("清除缓存应删除所有相关键")
        void invalidateCache_shouldDeleteKeys() {
            asyncStatisticsService.invalidateCache("app_001");

            verify(redisTemplate, times(1)).delete("stats:summary:app_001");
            verify(redisTemplate, times(1)).delete("stats:chart:app_001");
        }
    }

    @Nested
    @DisplayName("任务管理测试")
    class TaskManagementTests {

        @Test
        @DisplayName("提交摘要计算任务应返回任务ID")
        void submitSummaryCalculation_shouldReturnTaskId() {
            Map<String, Object> result = asyncStatisticsService.submitSummaryCalculation("app_001");

            assertNotNull(result);
            assertTrue(result.containsKey("taskId"));
            assertTrue(result.containsKey("appId"));
            assertEquals("app_001", result.get("appId"));
            assertEquals("submitted", result.get("status"));
        }

        @Test
        @DisplayName("任务ID应唯一")
        void submitSummaryCalculation_shouldReturnUniqueTaskId() {
            Map<String, Object> result1 = asyncStatisticsService.submitSummaryCalculation("app_001");
            Map<String, Object> result2 = asyncStatisticsService.submitSummaryCalculation("app_001");

            assertNotEquals(result1.get("taskId"), result2.get("taskId"));
        }

        @Test
        @DisplayName("提交图表计算任务应返回任务ID")
        void submitChartCalculation_shouldReturnTaskId() {
            Map<String, Object> result = asyncStatisticsService.submitChartCalculation("app_001");

            assertNotNull(result);
            assertTrue(result.containsKey("taskId"));
            assertTrue(result.containsKey("appId"));
            assertEquals("app_001", result.get("appId"));
            assertEquals("submitted", result.get("status"));
        }

        @Test
        @DisplayName("检查任务状态应返回正确状态")
        void getTaskStatus_shouldReturnStatus() {
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("totalDownloads", 1000L);

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("stats:summary:app_001")).thenReturn(cachedData);

            Map<String, Object> result = asyncStatisticsService.getTaskStatus("task_123", "app_001", "summary");

            assertNotNull(result);
            assertEquals("completed", result.get("status"));
            assertTrue(result.containsKey("data"));
        }

        @Test
        @DisplayName("任务未完成时应返回calculating状态")
        void getTaskStatus_notReady_shouldReturnCalculating() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("stats:summary:app_001")).thenReturn(null);

            Map<String, Object> result = asyncStatisticsService.getTaskStatus("task_123", "app_001", "summary");

            assertNotNull(result);
            assertEquals("calculating", result.get("status"));
            assertFalse(result.containsKey("data"));
        }

        @Test
        @DisplayName("等待任务完成应在超时时返回false")
        void waitForTask_completed_shouldReturnData() {
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("totalDownloads", 1000L);

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("stats:summary:app_001")).thenReturn(cachedData);

            Map<String, Object> result = asyncStatisticsService.waitForTask("app_001", "summary", 1);

            assertNotNull(result);
            assertEquals(true, result.get("ready"));
            assertTrue(result.containsKey("data"));
        }

        @Test
        @DisplayName("等待超时应返回not ready")
        void waitForTask_timeout_shouldReturnNotReady() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("stats:summary:app_001")).thenReturn(null);

            Map<String, Object> result = asyncStatisticsService.waitForTask("app_001", "summary", 1);

            assertNotNull(result);
            assertEquals(false, result.get("ready"));
            assertEquals("timeout", result.get("status"));
        }
    }

    @Nested
    @DisplayName("计算并缓存测试")
    class CalculateAndCacheTests {

        @Test
        @DisplayName("计算摘要并缓存应成功")
        void calculateAndCacheSummary_shouldSucceed() {
            LocalDate today = LocalDate.now();
            LocalDate weekAgo = today.minusDays(6);
            List<Statistics> statsData = Arrays.asList(
                TestDataBuilder.buildStatistics("app_001", weekAgo, 100L, 50L, 4.0)
            );

            when(statisticsRepository.findByAppIdAndStatDateBetween("app_001", weekAgo, today)).thenReturn(statsData);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            asyncStatisticsService.calculateAndCacheSummary("app_001");

            verify(valueOperations, times(1)).set(
                eq("stats:summary:app_001"),
                anyMap(),
                eq(30L),
                eq(TimeUnit.MINUTES)
            );
        }

        @Test
        @DisplayName("计算图表并缓存应成功")
        void calculateAndCacheChart_shouldSucceed() {
            LocalDate today = LocalDate.now();
            LocalDate weekAgo = today.minusDays(6);
            List<Statistics> statsData = Arrays.asList(
                TestDataBuilder.buildStatistics("app_001", weekAgo, 100L, 50L, 4.0)
            );

            when(statisticsRepository.findByAppIdAndStatDateBetween("app_001", weekAgo, today)).thenReturn(statsData);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            asyncStatisticsService.calculateAndCacheChart("app_001");

            verify(valueOperations, times(1)).set(
                eq("stats:chart:app_001"),
                anyMap(),
                eq(15L),
                eq(TimeUnit.MINUTES)
            );
        }

        @Test
        @DisplayName("计算异常时不应传播")
        void calculateAndCacheSummary_exception_shouldNotThrow() {
            when(statisticsRepository.findByAppIdAndStatDateBetween(anyString(), any(), any()))
                .thenThrow(new RuntimeException("Database error"));

            assertDoesNotThrow(() -> asyncStatisticsService.calculateAndCacheSummary("app_001"));
        }
    }

    @Nested
    @DisplayName("数据一致性测试")
    class DataConsistencyTests {

        @Test
        @DisplayName("计算结果应与数据库数据一致")
        void calculateSummary_shouldMatchDatabaseData() {
            LocalDate today = LocalDate.now();
            LocalDate weekAgo = today.minusDays(6);
            List<Statistics> statsData = Arrays.asList(
                TestDataBuilder.buildStatistics("app_001", weekAgo, 100L, 50L, 4.0),
                TestDataBuilder.buildStatistics("app_001", weekAgo.plusDays(1), 150L, 70L, 4.5)
            );

            when(statisticsRepository.findByAppIdAndStatDateBetween("app_001", weekAgo, today)).thenReturn(statsData);

            Map<String, Object> result = asyncStatisticsService.calculateSummary("app_001");

            assertEquals(250L, result.get("totalDownloads"));
            assertEquals(60L, result.get("avgDailyDownloads"));
            assertEquals(4.25, result.get("avgRating"));
        }

        @Test
        @DisplayName("缓存数据应与计算结果一致")
        void cacheSummary_shouldMatchCalculation() {
            LocalDate today = LocalDate.now();
            LocalDate weekAgo = today.minusDays(6);
            List<Statistics> statsData = Arrays.asList(
                TestDataBuilder.buildStatistics("app_001", weekAgo, 100L, 50L, 4.0)
            );

            when(statisticsRepository.findByAppIdAndStatDateBetween("app_001", weekAgo, today)).thenReturn(statsData);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            ArgumentCaptor<Map<String, Object>> cacheCaptor = ArgumentCaptor.forClass(Map.class);

            asyncStatisticsService.calculateAndCacheSummary("app_001");

            verify(valueOperations).set(eq("stats:summary:app_001"), cacheCaptor.capture(), anyLong(), any());
            Map<String, Object> cachedData = cacheCaptor.getValue();

            assertEquals(100L, cachedData.get("totalDownloads"));
            assertNotNull(cachedData.get("lastUpdated"));
        }
    }
}
