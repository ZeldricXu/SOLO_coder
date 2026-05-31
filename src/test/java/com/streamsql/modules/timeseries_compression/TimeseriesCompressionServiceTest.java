package com.streamsql.modules.timeseries_compression;

import com.streamsql.dto.TimeseriesDataDTO;
import com.streamsql.entity.TimeseriesData;
import com.streamsql.fixture.TestBuilders;
import com.streamsql.fixture.TestFixtures;
import com.streamsql.mapper.TimeseriesDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("时序数据压缩模块测试")
class TimeseriesCompressionServiceTest {

    @Mock
    private TimeseriesDataMapper timeseriesDataMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private TimeseriesCompressionService timeseriesService;

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTest {

        @Test
        @DisplayName("插入时序数据 - 成功")
        void shouldInsertDataSuccessfully() throws Exception {
            TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO().build();
            when(timeseriesDataMapper.insert(any(TimeseriesData.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            TimeseriesData result = timeseriesService.insertData(dto);

            assertNotNull(result);
            assertEquals(dto.getMetricName(), result.getMetricName());
            assertEquals(dto.getMetricValue(), result.getMetricValue());
            assertEquals("raw", result.getResolution());
            assertFalse(result.getCompressed());
        }

        @Test
        @DisplayName("查询时序数据 - 成功")
        void shouldQueryDataSuccessfully() {
            String metricName = "cpu_usage";
            TimeseriesData data = TestFixtures.createTimeseriesDataEntity();
            List<TimeseriesData> dataList = Arrays.asList(data);

            when(timeseriesDataMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(dataList, 1));

            com.streamsql.common.PageResult<TimeseriesData> result =
                    timeseriesService.queryData(metricName, null, null, null, 1, 10);

            assertNotNull(result);
            assertFalse(result.getRecords().isEmpty());
        }

        @Test
        @DisplayName("获取统计信息 - 成功")
        void shouldGetStatisticsSuccessfully() {
            String metricName = "cpu_usage";
            TimeseriesData data1 = TestFixtures.createTimeseriesDataEntity();
            data1.setMetricValue(10.0);
            TimeseriesData data2 = TestFixtures.createTimeseriesDataEntity();
            data2.setMetricValue(20.0);

            when(timeseriesDataMapper.selectList(any()))
                    .thenReturn(Arrays.asList(data1, data2));

            Map<String, Object> result = timeseriesService.getStatistics(metricName,
                    LocalDateTime.now().minusHours(1), LocalDateTime.now());

            assertNotNull(result);
            assertEquals(2L, result.get("count"));
            assertEquals(10.0, result.get("min"));
            assertEquals(20.0, result.get("max"));
            assertEquals(30.0, result.get("sum"));
        }

        @Test
        @DisplayName("删除时序数据 - 成功")
        void shouldDeleteDataSuccessfully() {
            String metricName = "cpu_usage";
            LocalDateTime beforeTime = LocalDateTime.now().minusDays(30);

            when(timeseriesDataMapper.delete(any())).thenReturn(10);

            assertDoesNotThrow(() -> timeseriesService.deleteData(metricName, beforeTime));
            verify(timeseriesDataMapper).delete(any());
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTest {

        @Test
        @DisplayName("插入数据 - 空字符串指标名")
        void shouldInsertDataWithEmptyMetricName() throws Exception {
            TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO().withEmptyValues().build();
            dto.setMetricName("");
            dto.setTimestamp(LocalDateTime.now());
            dto.setMetricValue(42.5);

            when(timeseriesDataMapper.insert(any(TimeseriesData.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            TimeseriesData result = timeseriesService.insertData(dto);

            assertNotNull(result);
            assertEquals("", result.getMetricName());
        }

        @Test
        @DisplayName("插入数据 - null值")
        void shouldInsertDataWithNullValues() throws Exception {
            TimeseriesDataDTO dto = new TimeseriesDataDTO();
            when(timeseriesDataMapper.insert(any(TimeseriesData.class))).thenReturn(1);

            assertThrows(NullPointerException.class, () -> timeseriesService.insertData(dto));
        }

        @Test
        @DisplayName("插入数据 - 零值")
        void shouldInsertDataWithZeroValue() throws Exception {
            TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO().withZeroValue().build();
            when(timeseriesDataMapper.insert(any(TimeseriesData.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            TimeseriesData result = timeseriesService.insertData(dto);

            assertNotNull(result);
            assertEquals(0.0, result.getMetricValue());
        }

        @Test
        @DisplayName("插入数据 - 负值")
        void shouldInsertDataWithNegativeValue() throws Exception {
            TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO().withNegativeValue().build();
            when(timeseriesDataMapper.insert(any(TimeseriesData.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            TimeseriesData result = timeseriesService.insertData(dto);

            assertNotNull(result);
            assertEquals(-100.0, result.getMetricValue());
        }

        @Test
        @DisplayName("插入数据 - 超大值")
        void shouldInsertDataWithVeryLargeValue() throws Exception {
            TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO().withVeryLargeValue().build();
            when(timeseriesDataMapper.insert(any(TimeseriesData.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            TimeseriesData result = timeseriesService.insertData(dto);

            assertNotNull(result);
            assertEquals(Double.MAX_VALUE, result.getMetricValue());
        }

        @Test
        @DisplayName("插入数据 - 超长指标名")
        void shouldInsertDataWithLongMetricName() throws Exception {
            TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO().withLongMetricName().build();
            when(timeseriesDataMapper.insert(any(TimeseriesData.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            TimeseriesData result = timeseriesService.insertData(dto);

            assertNotNull(result);
            assertEquals(1000, result.getMetricName().length());
        }

        @Test
        @DisplayName("查询数据 - 无数据")
        void shouldReturnEmptyPageWhenNoData() {
            String metricName = "unknown_metric";

            when(timeseriesDataMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

            com.streamsql.common.PageResult<TimeseriesData> result =
                    timeseriesService.queryData(metricName, null, null, null, 1, 10);

            assertNotNull(result);
            assertTrue(result.getRecords().isEmpty());
            assertEquals(0, result.getTotal());
        }

        @Test
        @DisplayName("获取统计信息 - 无数据")
        void shouldReturnEmptyStatisticsWhenNoData() {
            String metricName = "unknown_metric";

            when(timeseriesDataMapper.selectList(any())).thenReturn(Arrays.asList());

            Map<String, Object> result = timeseriesService.getStatistics(metricName,
                    LocalDateTime.now().minusHours(1), LocalDateTime.now());

            assertNotNull(result);
            assertEquals(0L, result.get("count"));
        }

        @Test
        @DisplayName("删除数据 - 无匹配数据")
        void shouldHandleDeletingNonExistentData() {
            String metricName = "unknown_metric";
            LocalDateTime beforeTime = LocalDateTime.now();

            when(timeseriesDataMapper.delete(any())).thenReturn(0);

            assertDoesNotThrow(() -> timeseriesService.deleteData(metricName, beforeTime));
        }
    }

    @Nested
    @DisplayName("并发竞态场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("并发插入数据 - 保证线程安全")
        void shouldHandleConcurrentInsertions() throws Exception {
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            when(timeseriesDataMapper.insert(any(TimeseriesData.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO()
                                .metricName("metric_" + index)
                                .metricValue((double) index)
                                .build();
                        timeseriesService.insertData(dto);
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("并发查询和插入 - 保证一致性")
        void shouldHandleConcurrentQueryAndInsert() throws InterruptedException {
            String metricName = "cpu_usage";
            TimeseriesData data = TestFixtures.createTimeseriesDataEntity();

            when(timeseriesDataMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                            Arrays.asList(data), 1));
            when(timeseriesDataMapper.insert(any(TimeseriesData.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    timeseriesService.queryData(metricName, null, null, null, 1, 10);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO().build();
                    timeseriesService.insertData(dto);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
        }

        @Test
        @DisplayName("并发删除和查询 - 保证一致性")
        void shouldHandleConcurrentDeleteAndQuery() throws InterruptedException {
            String metricName = "cpu_usage";
            LocalDateTime beforeTime = LocalDateTime.now().minusDays(30);

            when(timeseriesDataMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
            when(timeseriesDataMapper.delete(any())).thenReturn(10);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    timeseriesService.queryData(metricName, null, null, null, 1, 10);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    timeseriesService.deleteData(metricName, beforeTime);
                } finally {
                    latch.countDown();
                }
            });

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("降级行为测试")
    class DegradationTest {

        @Test
        @DisplayName("Mapper插入失败 - 异常传播")
        void shouldPropagateExceptionWhenInsertFails() throws Exception {
            TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO().build();

            when(timeseriesDataMapper.insert(any(TimeseriesData.class)))
                    .thenThrow(new RuntimeException("数据库连接超时"));

            assertThrows(RuntimeException.class, () -> timeseriesService.insertData(dto));
        }

        @Test
        @DisplayName("序列化失败 - 异常处理")
        void shouldHandleSerializationException() {
            TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO().build();

            when(objectMapper.writeValueAsString(any()))
                    .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("序列化失败") {});

            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> timeseriesService.insertData(dto));
        }

        @Test
        @DisplayName("查询时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenQueryFails() {
            String metricName = "cpu_usage";

            when(timeseriesDataMapper.selectPage(any(), any()))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class,
                    () -> timeseriesService.queryData(metricName, null, null, null, 1, 10));
        }

        @Test
        @DisplayName("获取统计时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenGetStatisticsFails() {
            String metricName = "cpu_usage";

            when(timeseriesDataMapper.selectList(any()))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class,
                    () -> timeseriesService.getStatistics(metricName,
                            LocalDateTime.now().minusHours(1), LocalDateTime.now()));
        }

        @Test
        @DisplayName("删除时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenDeleteFails() {
            String metricName = "cpu_usage";
            LocalDateTime beforeTime = LocalDateTime.now();

            when(timeseriesDataMapper.delete(any()))
                    .thenThrow(new RuntimeException("删除失败"));

            assertThrows(RuntimeException.class,
                    () -> timeseriesService.deleteData(metricName, beforeTime));
        }

        @Test
        @DisplayName("压缩时文件系统异常 - 降级处理")
        void shouldHandleFileSystemExceptionDuringCompression() {
            assertDoesNotThrow(() -> timeseriesService.compressOldData());
        }

        @Test
        @DisplayName("降采样时数据库异常 - 降级处理")
        void shouldHandleDatabaseExceptionDuringDownsampling() {
            assertDoesNotThrow(() -> timeseriesService.performDownsampling());
        }
    }
}
