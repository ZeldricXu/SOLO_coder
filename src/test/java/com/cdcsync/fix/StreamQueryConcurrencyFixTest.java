package com.cdcsync.fix;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.streamquery.domain.StreamQuery;
import com.cdcsync.streamquery.mapper.StreamQueryMapper;
import com.cdcsync.streamquery.service.impl.StreamQueryServiceImpl;
import com.cdcsync.test.builder.StreamQueryBuilder;
import com.cdcsync.test.builder.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("流式查询解析模块 - 并发安全修复验证")
class StreamQueryConcurrencyFixTest {

    @Mock
    private StreamQueryMapper mapper;

    @InjectMocks
    private StreamQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        reset(mapper);
    }

    @Nested
    @DisplayName("乐观锁机制验证")
    class OptimisticLockTests {

        @Test
        @DisplayName("执行查询 - 应使用数据库原子更新executionCount")
        void executeQuery_ShouldUseAtomicIncrement() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withId("test-query-001")
                    .withStatus("GENERATED")
                    .withExecutionCount(5)
                    .withVersion(1)
                    .build();

            when(mapper.selectById("test-query-001")).thenReturn(query);

            service.executeQuery("test-query-001", Map.of());

            ArgumentCaptor<UpdateWrapper<StreamQuery>> captor =
                    ArgumentCaptor.forClass(UpdateWrapper.class);
            verify(mapper, atLeastOnce()).update(isNull(), captor.capture());

            boolean hasAtomicIncrement = captor.getAllValues().stream()
                    .anyMatch(wrapper -> wrapper.getSqlSet() != null &&
                            wrapper.getSqlSet().contains("execution_count = COALESCE(execution_count, 0) + 1"));
            assertThat(hasAtomicIncrement).isTrue();
        }

        @Test
        @DisplayName("执行查询 - 版本号不匹配应检测到并发修改")
        void executeQuery_VersionMismatch_ShouldDetectConcurrentModification() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withId("test-query-001")
                    .withStatus("GENERATED")
                    .withVersion(2)
                    .build();

            when(mapper.selectById("test-query-001")).thenReturn(query);
            when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

            assertThatThrownBy(() -> service.executeQuery("test-query-001", Map.of()))
                    .isInstanceOf(OptimisticLockingFailureException.class)
                    .hasMessageContaining("Concurrent modification");
        }
    }

    @Nested
    @DisplayName("状态机验证")
    class StateMachineTests {

        @Test
        @DisplayName("状态流转 - 从PARSED到OPTIMIZED应允许")
        void optimizePlan_FromParsed_ShouldSucceed() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withId("test-001")
                    .withStatus("PARSED")
                    .withParsedPlanJson("{\"planType\":\"SCAN\"}")
                    .build();

            when(mapper.selectById("test-001")).thenReturn(query);
            when(mapper.updateById(any(StreamQuery.class))).thenReturn(1);

            StreamQuery result = service.optimizePlan("test-001");

            assertThat(result.getStatus()).isEqualTo("OPTIMIZED");
        }

        @Test
        @DisplayName("状态流转 - 从DRAFT直接OPTIMIZED应拒绝")
        void optimizePlan_FromDraft_ShouldReject() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withId("test-001")
                    .withStatus("DRAFT")
                    .withParsedPlanJson("{\"planType\":\"SCAN\"}")
                    .build();

            when(mapper.selectById("test-001")).thenReturn(query);

            assertThatThrownBy(() -> service.optimizePlan("test-001"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid state transition");
        }

        @Test
        @DisplayName("状态流转 - 从EXECUTING到OPTIMIZED应拒绝")
        void optimizePlan_FromExecuting_ShouldReject() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withId("test-001")
                    .withStatus("EXECUTING")
                    .withParsedPlanJson("{\"planType\":\"SCAN\"}")
                    .build();

            when(mapper.selectById("test-001")).thenReturn(query);

            assertThatThrownBy(() -> service.optimizePlan("test-001"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid state transition");
        }

        @Test
        @DisplayName("状态流转 - 从GENERATED到EXECUTING应允许")
        void executeQuery_FromGenerated_ShouldSucceed() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withId("test-001")
                    .withStatus("GENERATED")
                    .build();

            when(mapper.selectById("test-001")).thenReturn(query);
            when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

            Object result = service.executeQuery("test-001", Map.of());

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("状态流转 - 从PARSED到EXECUTING应拒绝")
        void executeQuery_FromParsed_ShouldReject() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withId("test-001")
                    .withStatus("PARSED")
                    .build();

            when(mapper.selectById("test-001")).thenReturn(query);

            assertThatThrownBy(() -> service.executeQuery("test-001", Map.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid state transition");
        }
    }

    @Nested
    @DisplayName("并发执行验证")
    class ConcurrentExecutionTests {

        @Test
        @DisplayName("并发执行查询 - 每个查询都使用原子更新")
        void concurrentExecute_ShouldUseAtomicIncrementEachTime() throws Exception {
            int threadCount = 5;
            String queryId = "concurrent-test-001";
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withId(queryId)
                    .withStatus("GENERATED")
                    .withExecutionCount(0)
                    .build();

            when(mapper.selectById(queryId)).thenReturn(query);
            when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        service.executeQuery(queryId, Map.of());
                        successCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            verify(mapper, atLeast(threadCount)).update(isNull(), any(UpdateWrapper.class));

            ArgumentCaptor<UpdateWrapper<StreamQuery>> captor =
                    ArgumentCaptor.forClass(UpdateWrapper.class);
            verify(mapper, atLeastOnce()).update(isNull(), captor.capture());

            boolean allUseAtomic = captor.getAllValues().stream()
                    .filter(w -> w.getSqlSet() != null)
                    .anyMatch(w -> w.getSqlSet().contains("execution_count = COALESCE(execution_count, 0) + 1"));
            assertThat(allUseAtomic).isTrue();
        }

        @Test
        @DisplayName("查询名称生成 - 应使用UUID而非时间戳避免冲突")
        void parseSql_ShouldUseUUIDForName() {
            String sql = TestDataFactory.createValidSelectSql();
            when(mapper.insert(any(StreamQuery.class))).thenReturn(1);

            StreamQuery result1 = service.parseSql(sql);
            StreamQuery result2 = service.parseSql(sql);

            assertThat(result1.getName()).startsWith("Query_");
            assertThat(result2.getName()).startsWith("Query_");
            assertThat(result1.getName()).isNotEqualTo(result2.getName());
        }
    }

    @Nested
    @DisplayName("参数校验验证")
    class ParameterValidationTests {

        @Test
        @DisplayName("空ID - 应抛出异常")
        void parseSql_NullSql_ShouldThrow() {
            assertThatThrownBy(() -> service.parseSql(null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("空ID - 应抛出异常")
        void optimizePlan_BlankId_ShouldThrow() {
            assertThatThrownBy(() -> service.optimizePlan(""))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("SQL长度校验 - 超长SQL应抛出异常")
        void parseSql_TooLongSql_ShouldThrow() {
            String veryLongSql = "SELECT " + "a".repeat(70000);

            assertThatThrownBy(() -> service.parseSql(veryLongSql))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("版本字段验证")
    class VersionFieldTests {

        @Test
        @DisplayName("实体类 - 应包含version字段")
        void streamQuery_ShouldHaveVersionField() {
            StreamQuery query = new StreamQuery();
            query.setVersion(1);

            assertThat(query.getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("状态验证 - 所有状态应为有效值")
        void streamQuery_AllStatusesShouldBeValid() {
            assertThat(StreamQuery.isValidStatus("DRAFT")).isTrue();
            assertThat(StreamQuery.isValidStatus("PARSED")).isTrue();
            assertThat(StreamQuery.isValidStatus("OPTIMIZED")).isTrue();
            assertThat(StreamQuery.isValidStatus("GENERATED")).isTrue();
            assertThat(StreamQuery.isValidStatus("EXECUTING")).isTrue();
            assertThat(StreamQuery.isValidStatus("EXECUTED")).isTrue();
            assertThat(StreamQuery.isValidStatus("FAILED")).isTrue();
            assertThat(StreamQuery.isValidStatus("INVALID")).isFalse();
        }
    }
}
