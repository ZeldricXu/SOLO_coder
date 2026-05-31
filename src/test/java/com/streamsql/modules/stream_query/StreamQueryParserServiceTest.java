package com.streamsql.modules.stream_query;

import com.streamsql.dto.StreamQueryDTO;
import com.streamsql.entity.StreamQueryPlan;
import com.streamsql.fixture.TestBuilders;
import com.streamsql.fixture.TestFixtures;
import com.streamsql.mapper.StreamQueryPlanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("流式查询解析模块测试")
class StreamQueryParserServiceTest {

    @Mock
    private StreamQueryPlanMapper streamQueryPlanMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private StreamQueryParserService streamQueryParserService;

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTest {

        @Test
        @DisplayName("解析SQL查询 - 成功")
        void shouldParseQuerySuccessfully() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().build();
            when(streamQueryPlanMapper.insert(any(StreamQueryPlan.class))).thenReturn(1);

            StreamQueryPlan result = streamQueryParserService.parseAndPlan(dto);

            assertNotNull(result);
            assertEquals(dto.getSql(), result.getSql());
            assertNotNull(result.getLogicalPlan());
            assertNotNull(result.getPhysicalPlan());
        }

        @Test
        @DisplayName("查询解析计划 - 成功")
        void shouldGetQueryPlanSuccessfully() {
            String planId = "plan_001";
            StreamQueryPlan expectedPlan = TestFixtures.createStreamQueryPlanEntity();
            expectedPlan.setPlanId(planId);

            when(streamQueryPlanMapper.selectById(planId)).thenReturn(expectedPlan);

            StreamQueryPlan result = streamQueryParserService.getPlan(planId);

            assertNotNull(result);
            assertEquals(planId, result.getPlanId());
        }

        @Test
        @DisplayName("列出查询计划 - 成功")
        void shouldListPlansSuccessfully() {
            StreamQueryPlan plan = TestFixtures.createStreamQueryPlanEntity();
            List<StreamQueryPlan> plans = Arrays.asList(plan);

            when(streamQueryPlanMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(plans, 1));

            com.streamsql.common.PageResult<StreamQueryPlan> result =
                    streamQueryParserService.listPlans(1, 10, null);

            assertNotNull(result);
            assertFalse(result.getRecords().isEmpty());
        }

        @Test
        @DisplayName("删除查询计划 - 成功")
        void shouldDeletePlanSuccessfully() {
            String planId = "plan_001";
            when(streamQueryPlanMapper.deleteById(planId)).thenReturn(1);

            assertDoesNotThrow(() -> streamQueryParserService.deletePlan(planId));
            verify(streamQueryPlanMapper).deleteById(planId);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTest {

        @Test
        @DisplayName("解析空SQL - 异常处理")
        void shouldHandleEmptySql() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().withEmptySql().build();

            assertThrows(Exception.class, () -> streamQueryParserService.parseAndPlan(dto));
        }

        @Test
        @DisplayName("解析null SQL - 异常处理")
        void shouldHandleNullSql() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().withNullSql().build();

            assertThrows(Exception.class, () -> streamQueryParserService.parseAndPlan(dto));
        }

        @Test
        @DisplayName("解析无效SQL - 异常处理")
        void shouldHandleInvalidSql() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().withInvalidSql().build();

            assertThrows(Exception.class, () -> streamQueryParserService.parseAndPlan(dto));
        }

        @Test
        @DisplayName("解析超长SQL - 异常处理")
        void shouldHandleLongSql() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().withLongSql().build();
            when(streamQueryPlanMapper.insert(any(StreamQueryPlan.class))).thenReturn(1);

            assertDoesNotThrow(() -> streamQueryParserService.parseAndPlan(dto));
        }

        @Test
        @DisplayName("解析SQL - 超时为0")
        void shouldHandleZeroTimeout() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().timeout(0).build();
            when(streamQueryPlanMapper.insert(any(StreamQueryPlan.class))).thenReturn(1);

            StreamQueryPlan result = streamQueryParserService.parseAndPlan(dto);
            assertNotNull(result);
        }

        @Test
        @DisplayName("解析SQL - 超时为负数")
        void shouldHandleNegativeTimeout() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().timeout(-1).build();
            when(streamQueryPlanMapper.insert(any(StreamQueryPlan.class))).thenReturn(1);

            StreamQueryPlan result = streamQueryParserService.parseAndPlan(dto);
            assertNotNull(result);
        }

        @Test
        @DisplayName("查询计划 - 不存在的ID")
        void shouldReturnNullForNonExistentPlan() {
            String nonExistentId = "non_existent";

            when(streamQueryPlanMapper.selectById(nonExistentId)).thenReturn(null);

            StreamQueryPlan result = streamQueryParserService.getPlan(nonExistentId);
            assertNull(result);
        }

        @Test
        @DisplayName("列出计划 - 空结果")
        void shouldReturnEmptyPageWhenNoPlans() {
            when(streamQueryPlanMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

            com.streamsql.common.PageResult<StreamQueryPlan> result =
                    streamQueryParserService.listPlans(1, 10, null);

            assertNotNull(result);
            assertTrue(result.getRecords().isEmpty());
            assertEquals(0, result.getTotal());
        }

        @Test
        @DisplayName("删除计划 - 不存在的ID")
        void shouldHandleDeletingNonExistentPlan() {
            String nonExistentId = "non_existent";
            when(streamQueryPlanMapper.deleteById(nonExistentId)).thenReturn(0);

            assertDoesNotThrow(() -> streamQueryParserService.deletePlan(nonExistentId));
        }

        @Test
        @DisplayName("最大记录数为0")
        void shouldHandleZeroMaxRecords() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().maxRecords(0).build();
            when(streamQueryPlanMapper.insert(any(StreamQueryPlan.class))).thenReturn(1);

            StreamQueryPlan result = streamQueryParserService.parseAndPlan(dto);
            assertNotNull(result);
        }

        @Test
        @DisplayName("最大记录数为负数")
        void shouldHandleNegativeMaxRecords() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().maxRecords(-1).build();
            when(streamQueryPlanMapper.insert(any(StreamQueryPlan.class))).thenReturn(1);

            StreamQueryPlan result = streamQueryParserService.parseAndPlan(dto);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("并发竞态场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("并发解析查询 - 保证线程安全")
        void shouldHandleConcurrentParsing() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            when(streamQueryPlanMapper.insert(any(StreamQueryPlan.class))).thenReturn(1);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        StreamQueryDTO dto = TestBuilders.streamQueryDTO()
                                .sql("SELECT * FROM users WHERE id = " + index)
                                .build();
                        streamQueryParserService.parseAndPlan(dto);
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
        @DisplayName("并发查询和删除 - 保证一致性")
        void shouldHandleConcurrentQueryAndDelete() throws InterruptedException {
            String planId = "plan_001";
            StreamQueryPlan plan = TestFixtures.createStreamQueryPlanEntity();
            plan.setPlanId(planId);

            when(streamQueryPlanMapper.selectById(planId)).thenReturn(plan);
            when(streamQueryPlanMapper.deleteById(planId)).thenReturn(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    streamQueryParserService.getPlan(planId);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    streamQueryParserService.deletePlan(planId);
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
        void shouldPropagateExceptionWhenInsertFails() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO().build();

            when(streamQueryPlanMapper.insert(any(StreamQueryPlan.class)))
                    .thenThrow(new RuntimeException("数据库连接超时"));

            assertThrows(RuntimeException.class, () -> streamQueryParserService.parseAndPlan(dto));
        }

        @Test
        @DisplayName("SQL解析异常 - 降级处理")
        void shouldHandleSqlParsingExceptionGracefully() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO()
                    .sql("THIS IS NOT VALID SQL AT ALL !@#$%")
                    .build();

            assertThrows(Exception.class, () -> streamQueryParserService.parseAndPlan(dto));
        }

        @Test
        @DisplayName("查询计划时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenQueryFails() {
            String planId = "plan_001";

            when(streamQueryPlanMapper.selectById(planId))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class, () -> streamQueryParserService.getPlan(planId));
        }

        @Test
        @DisplayName("删除计划时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenDeleteFails() {
            String planId = "plan_001";

            when(streamQueryPlanMapper.deleteById(planId))
                    .thenThrow(new RuntimeException("删除失败"));

            assertThrows(RuntimeException.class, () -> streamQueryParserService.deletePlan(planId));
        }

        @Test
        @DisplayName("复杂SQL解析 - 性能降级")
        void shouldHandleComplexSqlWithPerformanceDegradation() {
            StringBuilder complexSql = new StringBuilder("SELECT ");
            for (int i = 0; i < 100; i++) {
                if (i > 0) complexSql.append(", ");
                complexSql.append("t").append(i).append(".col").append(i);
            }
            complexSql.append(" FROM table1 t1");
            for (int i = 2; i <= 50; i++) {
                complexSql.append(" JOIN table").append(i).append(" t").append(i)
                        .append(" ON t1.id = t").append(i).append(".id");
            }

            StreamQueryDTO dto = TestBuilders.streamQueryDTO().sql(complexSql.toString()).build();
            when(streamQueryPlanMapper.insert(any(StreamQueryPlan.class))).thenReturn(1);

            long startTime = System.currentTimeMillis();
            StreamQueryPlan result = streamQueryParserService.parseAndPlan(dto);
            long duration = System.currentTimeMillis() - startTime;

            assertNotNull(result);
            assertTrue(duration < 10000, "复杂SQL解析应在10秒内完成");
        }
    }
}
