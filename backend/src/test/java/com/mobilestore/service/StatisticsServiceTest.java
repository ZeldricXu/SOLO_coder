package com.mobilestore.service;

import com.mobilestore.repository.FeedbackRepository;
import com.mobilestore.repository.StatisticsRepository;
import com.mobilestore.repository.VersionRepository;
import com.mobilestore.test.BaseServiceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("统计服务测试")
class StatisticsServiceTest extends BaseServiceTest {

    @Mock
    private StatisticsRepository statisticsRepository;

    @Mock
    private VersionRepository versionRepository;

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private AsyncStatisticsService asyncStatisticsService;

    @InjectMocks
    private StatisticsService statisticsService;

    @Nested
    @DisplayName("摘要统计查询测试")
    class SummaryStatisticsTests {

        @Test
        @DisplayName("缓存命中时应直接返回数据")
        void getSummaryStatistics_cacheHit_shouldReturnData() {
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("totalDownloads", 1000L);
            cachedData.put("avgRating", 4.5);
            cachedData.put("lastUpdated", "2024-01-01T12:00:00");

            when(asyncStatisticsService.getCachedSummary("app_001")).thenReturn(cachedData);

            Map<String, Object> result = statisticsService.getSummaryStatistics("app_001");

            assertNotNull(result);
            assertEquals(true, result.get("cacheHit"));
            assertTrue(((Map<?, ?>) result.get("data")).containsKey("totalDownloads"));
        }

        @Test
        @DisplayName("缓存未命中时应提交异步任务")
        void getSummaryStatistics_cacheMiss_shouldSubmitTask() {
            when(asyncStatisticsService.getCachedSummary("app_001")).thenReturn(null);
            Map<String, Object> taskResponse = new HashMap<>();
            taskResponse.put("taskId", "task_123");
            taskResponse.put("appId", "app_001");
            when(asyncStatisticsService.submitSummaryCalculation("app_001")).thenReturn(taskResponse);

            Map<String, Object> result = statisticsService.getSummaryStatistics("app_001");

            assertNotNull(result);
            assertEquals(false, result.get("cacheHit"));
            assertEquals("submitted", result.get("status"));
            assertEquals("task_123", result.get("taskId"));
            verify(asyncStatisticsService, times(1)).submitSummaryCalculation("app_001");
        }

        @Test
        @DisplayName("等待摘要统计应在缓存就绪时返回")
        void waitForSummaryStatistics_ready_shouldReturnData() {
            Map<String, Object> waitResponse = new HashMap<>();
            waitResponse.put("ready", true);
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("totalDownloads", 1000L);
            waitResponse.put("data", cachedData);

            when(asyncStatisticsService.waitForTask("app_001", "summary", 30)).thenReturn(waitResponse);

            Map<String, Object> result = statisticsService.waitForSummaryStatistics("app_001", 30);

            assertNotNull(result);
            assertTrue(((Map<?, ?>) result.get("data")).containsKey("ready"));
        }

        @Test
        @DisplayName("等待超时应返回timeout状态")
        void waitForSummaryStatistics_timeout_shouldReturnTimeout() {
            Map<String, Object> waitResponse = new HashMap<>();
            waitResponse.put("ready", false);
            waitResponse.put("status", "timeout");

            when(asyncStatisticsService.waitForTask("app_001", "summary", 5)).thenReturn(waitResponse);

            Map<String, Object> result = statisticsService.waitForSummaryStatistics("app_001", 5);

            assertNotNull(result);
            assertEquals(false, ((Map<?, ?>) result.get("data")).get("ready"));
            assertEquals("timeout", ((Map<?, ?>) result.get("data")).get("status"));
        }
    }

    @Nested
    @DisplayName("图表统计查询测试")
    class ChartStatisticsTests {

        @Test
        @DisplayName("缓存命中时应直接返回图表数据")
        void getChartStatistics_cacheHit_shouldReturnData() {
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("dates", Arrays.asList("2024-01-01", "2024-01-02"));
            cachedData.put("downloads", Arrays.asList(100L, 200L));

            when(asyncStatisticsService.getCachedChart("app_001")).thenReturn(cachedData);

            Map<String, Object> result = statisticsService.getChartStatistics("app_001");

            assertNotNull(result);
            assertEquals(true, result.get("cacheHit"));
        }

        @Test
        @DisplayName("缓存未命中时应提交异步任务")
        void getChartStatistics_cacheMiss_shouldSubmitTask() {
            when(asyncStatisticsService.getCachedChart("app_001")).thenReturn(null);
            Map<String, Object> taskResponse = new HashMap<>();
            taskResponse.put("taskId", "task_456");
            when(asyncStatisticsService.submitChartCalculation("app_001")).thenReturn(taskResponse);

            Map<String, Object> result = statisticsService.getChartStatistics("app_001");

            assertNotNull(result);
            assertEquals(false, result.get("cacheHit"));
            assertEquals("task_456", result.get("taskId"));
        }

        @Test
        @DisplayName("等待图表统计应在就绪时返回")
        void waitForChartStatistics_ready_shouldReturnData() {
            Map<String, Object> waitResponse = new HashMap<>();
            waitResponse.put("ready", true);
            waitResponse.put("data", new HashMap<>());

            when(asyncStatisticsService.waitForTask("app_001", "chart", 30)).thenReturn(waitResponse);

            Map<String, Object> result = statisticsService.waitForChartStatistics("app_001", 30);

            assertNotNull(result);
            assertTrue(((Map<?, ?>) result.get("data")).containsKey("ready"));
        }
    }

    @Nested
    @DisplayName("强制刷新测试")
    class ForceRefreshTests {

        @Test
        @DisplayName("刷新摘要统计应先清除缓存")
        void refreshSummary_shouldInvalidateCacheFirst() {
            Map<String, Object> taskResponse = new HashMap<>();
            taskResponse.put("taskId", "task_refresh_123");
            when(asyncStatisticsService.submitSummaryCalculation("app_001")).thenReturn(taskResponse);

            Map<String, Object> result = statisticsService.refreshSummaryStatistics("app_001");

            verify(asyncStatisticsService, times(1)).invalidateCache("app_001");
            verify(asyncStatisticsService, times(1)).submitSummaryCalculation("app_001");
            assertEquals("task_refresh_123", result.get("taskId"));
        }

        @Test
        @DisplayName("刷新图表统计应先清除缓存")
        void refreshChart_shouldInvalidateCacheFirst() {
            Map<String, Object> taskResponse = new HashMap<>();
            taskResponse.put("taskId", "task_refresh_456");
            when(asyncStatisticsService.submitChartCalculation("app_001")).thenReturn(taskResponse);

            Map<String, Object> result = statisticsService.refreshChartStatistics("app_001");

            verify(asyncStatisticsService, times(1)).invalidateCache("app_001");
            verify(asyncStatisticsService, times(1)).submitChartCalculation("app_001");
            assertEquals("task_refresh_456", result.get("taskId"));
        }

        @Test
        @DisplayName("刷新应返回recomputing状态")
        void refreshSummary_shouldReturnRecomputingStatus() {
            Map<String, Object> taskResponse = new HashMap<>();
            taskResponse.put("taskId", "task_789");
            when(asyncStatisticsService.submitSummaryCalculation("app_001")).thenReturn(taskResponse);

            Map<String, Object> result = statisticsService.refreshSummaryStatistics("app_001");

            assertEquals("recomputing", result.get("status"));
            assertEquals("app_001", result.get("appId"));
        }
    }

    @Nested
    @DisplayName("任务状态查询测试")
    class TaskStatusTests {

        @Test
        @DisplayName("查询任务状态应调用异步服务")
        void getTaskStatus_shouldCallAsyncService() {
            Map<String, Object> statusResponse = new HashMap<>();
            statusResponse.put("status", "completed");

            when(asyncStatisticsService.getTaskStatus("task_123", "app_001", "summary"))
                .thenReturn(statusResponse);

            Map<String, Object> result = statisticsService.getTaskStatus("task_123", "app_001", "summary");

            assertNotNull(result);
            assertEquals("completed", result.get("status"));
            verify(asyncStatisticsService, times(1)).getTaskStatus("task_123", "app_001", "summary");
        }

        @Test
        @DisplayName("摘要类型任务应传递正确参数")
        void getTaskStatus_summaryType_shouldUseSummary() {
            Map<String, Object> statusResponse = new HashMap<>();
            statusResponse.put("status", "calculating");

            when(asyncStatisticsService.getTaskStatus("task_abc", "app_001", "summary"))
                .thenReturn(statusResponse);

            statisticsService.getTaskStatus("task_abc", "app_001", "summary");

            verify(asyncStatisticsService).getTaskStatus(eq("task_abc"), eq("app_001"), eq("summary"));
        }

        @Test
        @DisplayName("图表类型任务应传递正确参数")
        void getTaskStatus_chartType_shouldUseChart() {
            Map<String, Object> statusResponse = new HashMap<>();
            statusResponse.put("status", "completed");

            when(asyncStatisticsService.getTaskStatus("task_xyz", "app_001", "chart"))
                .thenReturn(statusResponse);

            statisticsService.getTaskStatus("task_xyz", "app_001", "chart");

            verify(asyncStatisticsService).getTaskStatus(eq("task_xyz"), eq("app_001"), eq("chart"));
        }
    }

    @Nested
    @DisplayName("缓存信息查询测试")
    class CacheInfoTests {

        @Test
        @DisplayName("查询缓存摘要应返回缓存状态")
        void getCacheSummary_shouldReturnCacheInfo() {
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("totalDownloads", 1000L);
            cachedData.put("lastUpdated", "2024-01-01T12:00:00");

            when(asyncStatisticsService.getCachedSummary("app_001")).thenReturn(cachedData);

            Map<String, Object> result = statisticsService.getCacheSummary("app_001");

            assertNotNull(result);
            assertEquals(true, result.get("exists"));
            assertNotNull(result.get("lastUpdated"));
            assertEquals(cachedData, result.get("data"));
        }

        @Test
        @DisplayName("无缓存时应返回不存在状态")
        void getCacheSummary_noCache_shouldReturnNotExists() {
            when(asyncStatisticsService.getCachedSummary("app_001")).thenReturn(null);

            Map<String, Object> result = statisticsService.getCacheSummary("app_001");

            assertNotNull(result);
            assertEquals(false, result.get("exists"));
            assertFalse(result.containsKey("data"));
        }
    }

    @Nested
    @DisplayName("响应格式测试")
    class ResponseFormatTests {

        @Test
        @DisplayName("缓存命中响应应包含正确字段")
        void cacheHitResponse_shouldHaveCorrectFields() {
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("totalDownloads", 1000L);

            when(asyncStatisticsService.getCachedSummary("app_001")).thenReturn(cachedData);

            Map<String, Object> result = statisticsService.getSummaryStatistics("app_001");

            assertTrue(result.containsKey("cacheHit"));
            assertTrue(result.containsKey("data"));
            assertTrue(result.containsKey("appId"));
            assertEquals(true, result.get("cacheHit"));
        }

        @Test
        @DisplayName("缓存未命中响应应包含任务ID")
        void cacheMissResponse_shouldHaveTaskId() {
            when(asyncStatisticsService.getCachedSummary("app_001")).thenReturn(null);
            Map<String, Object> taskResponse = new HashMap<>();
            taskResponse.put("taskId", "task_123");
            taskResponse.put("appId", "app_001");
            taskResponse.put("status", "submitted");
            when(asyncStatisticsService.submitSummaryCalculation("app_001")).thenReturn(taskResponse);

            Map<String, Object> result = statisticsService.getSummaryStatistics("app_001");

            assertTrue(result.containsKey("cacheHit"));
            assertTrue(result.containsKey("taskId"));
            assertTrue(result.containsKey("status"));
            assertEquals(false, result.get("cacheHit"));
            assertEquals("submitted", result.get("status"));
        }

        @Test
        @DisplayName("刷新响应应包含刷新时间")
        void refreshResponse_shouldHaveRefreshTime() {
            Map<String, Object> taskResponse = new HashMap<>();
            taskResponse.put("taskId", "task_refresh");
            when(asyncStatisticsService.submitSummaryCalculation("app_001")).thenReturn(taskResponse);

            Map<String, Object> result = statisticsService.refreshSummaryStatistics("app_001");

            assertTrue(result.containsKey("refreshedAt"));
            assertEquals("recomputing", result.get("status"));
        }
    }

    @Nested
    @DisplayName("查询优化测试")
    class QueryOptimizationTests {

        @Test
        @DisplayName("查询摘要应优先使用缓存")
        void getSummary_shouldCheckCacheFirst() {
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("totalDownloads", 1000L);

            when(asyncStatisticsService.getCachedSummary("app_001")).thenReturn(cachedData);

            statisticsService.getSummaryStatistics("app_001");

            verify(asyncStatisticsService, times(1)).getCachedSummary("app_001");
            verify(asyncStatisticsService, never()).submitSummaryCalculation(anyString());
        }

        @Test
        @DisplayName("查询图表应优先使用缓存")
        void getChart_shouldCheckCacheFirst() {
            Map<String, Object> cachedData = new HashMap<>();
            cachedData.put("dates", Arrays.asList("2024-01-01"));

            when(asyncStatisticsService.getCachedChart("app_001")).thenReturn(cachedData);

            statisticsService.getChartStatistics("app_001");

            verify(asyncStatisticsService, times(1)).getCachedChart("app_001");
            verify(asyncStatisticsService, never()).submitChartCalculation(anyString());
        }
    }
}
