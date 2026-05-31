package com.cdcsync.streamquery;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StreamQueryServiceImpl 单元测试 - 数据一致性保障")
class StreamQueryServiceImplTest {

    @Mock
    private StreamQueryMapper mapper;

    @InjectMocks
    private StreamQueryServiceImpl service;

    private StreamQuery sampleQuery;

    @BeforeEach
    void setUp() {
        sampleQuery = StreamQueryBuilder.aStreamQuery()
                .withDefaults()
                .withId("test-query-001")
                .build();
    }

    @Nested
    @DisplayName("SQL解析数据一致性测试")
    class ParseSqlConsistencyTests {

        @Test
        @DisplayName("解析SQL - 应正确设置状态和JSON")
        void parseSql_ShouldSetStatusAndParsedJson() {
            String sql = TestDataFactory.createValidSelectSql();
            when(mapper.insert(any(StreamQuery.class))
            .thenAnswer(invocation -> {
                StreamQuery arg = invocation.getArgument(0);
                assertThat(arg.getStatus()).isEqualTo("PARSED");
                assertThat(arg.getSqlText()).isEqualTo(sql);
                assertThat(arg.getParsedPlanJson()).isNotNull().isNotEmpty();
                assertThat(arg.getExecutionCount()).isEqualTo(0);
                return 1;
            });

            StreamQuery result = service.parseSql(sql);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("PARSED");
            verify(mapper, times(1)).insert(any(StreamQuery.class));
        }

        @Test
        @DisplayName("解析SQL - 解析无效SQL应抛出异常且不写入数据库")
        void parseSql_InvalidSql_ShouldThrowAndNotPersist() {
            String invalidSql = TestDataFactory.createInvalidSql();

            assertThatThrownBy(() -> service.parseSql(invalidSql))
                    .isInstanceOf(BusinessException.class);

            verify(mapper, never()).insert(any());
        }
    }

    @Nested
    @DisplayName("计划优化数据一致性测试")
    class OptimizePlanConsistencyTests {

        @Test
        @DisplayName("优化计划 - 应验证查询存在且已解析")
        void optimizePlan_QueryNotFound_ShouldThrowException() {
            when(mapper.selectById("non-existent")).thenReturn(null);

            assertThatThrownBy(() -> service.optimizePlan("non-existent"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Query not found");
        }

        @Test
        @DisplayName("优化计划 - 查询未解析应抛出异常")
        void optimizePlan_NotParsed_ShouldThrowException() {
            StreamQuery unparsedQuery = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withParsedPlanJson(null)
                    .build();

            when(mapper.selectById(unparsedQuery.getId())).thenReturn(unparsedQuery);

            assertThatThrownBy(() -> service.optimizePlan(unparsedQuery.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not been parsed");
        }

        @Test
        @DisplayName("优化计划 - 成功后状态应更新为OPTIMIZED")
        void optimizePlan_Success_ShouldUpdateStatus() {
            StreamQuery parsedQuery = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withParsedPlanJson("{\"planType\":\"PROJECT\"}")
                    .build();

            when(mapper.selectById(parsedQuery.getId())).thenReturn(parsedQuery);
            when(mapper.updateById(any(StreamQuery.class))).thenReturn(1);

            StreamQuery result = service.optimizePlan(parsedQuery.getId());

            assertThat(result.getStatus()).isEqualTo("OPTIMIZED");
            assertThat(result.getOptimizedPlanJson()).isNotNull().isNotEmpty();
            verify(mapper, times(1)).updateById(any(StreamQuery.class));
        }
    }

    @Nested
    @DisplayName("物理计划生成数据一致性测试")
    class PhysicalPlanConsistencyTests {

        @Test
        @DisplayName("生成物理计划 - 查询不存在应抛出异常")
        void generatePhysicalPlan_NotFound_ShouldThrowException() {
            when(mapper.selectById("non-existent")).thenReturn(null);

            assertThatThrownBy(() -> service.generatePhysicalPlan("non-existent"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("生成物理计划 - 使用优化后的计划（如果有）")
        void generatePhysicalPlan_WithOptimizedPlan_ShouldUseIt() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withParsedPlanJson("{\"planType\":\"SCAN\"}")
                    .withOptimizedPlanJson("{\"planType\":\"SCAN\",\"optimized\":true}")
                    .build();

            when(mapper.selectById(query.getId())).thenReturn(query);
            when(mapper.updateById(any(StreamQuery.class))).thenReturn(1);

            StreamQuery result = service.generatePhysicalPlan(query.getId());

            assertThat(result.getStatus()).isEqualTo("GENERATED");
            assertThat(result.getPhysicalPlanJson()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("生成物理计划 - 未解析或优化应抛出异常")
        void generatePhysicalPlan_NoPlan_ShouldThrowException() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withParsedPlanJson(null)
                    .withOptimizedPlanJson(null)
                    .build();

            when(mapper.selectById(query.getId())).thenReturn(query);

            assertThatThrownBy(() -> service.generatePhysicalPlan(query.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not been parsed or optimized");
        }
    }

    @Nested
    @DisplayName("查询执行数据一致性测试")
    class ExecuteQueryConsistencyTests {

        @Test
        @DisplayName("执行查询 - 应正确更新执行计数和状态")
        void executeQuery_ShouldUpdateExecutionCount() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withExecutionCount(5)
                    .build();

            when(mapper.selectById(query.getId())).thenReturn(query);
            when(mapper.updateById(any(StreamQuery.class))).thenReturn(1);

            Object result = service.executeQuery(query.getId(), Map.of("param1", "value1"));

            assertThat(result).isNotNull();
            Map<?, ?> resultMap = (Map<?, ?>) result;
            assertThat(resultMap).containsKey("rows");
            assertThat(resultMap).containsEntry("totalRows", 2);

            verify(mapper, times(2)).updateById(any(StreamQuery.class));
        }

        @Test
        @DisplayName("执行查询 - 首次执行应设置执行计数为1")
        void executeQuery_FirstExecution_ShouldSetCountToOne() {
            StreamQuery query = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .withExecutionCount(null)
                    .build();

            when(mapper.selectById(query.getId())).thenReturn(query);
            when(mapper.updateById(any(StreamQuery.class))).thenReturn(1);

            service.executeQuery(query.getId(), Map.of());

            verify(mapper, times(2)).updateById(any(StreamQuery.class));
        }

        @Test
        @DisplayName("执行查询 - 查询不存在应抛出异常")
        void executeQuery_NotFound_ShouldThrowException() {
            when(mapper.selectById("non-existent")).thenReturn(null);

            assertThatThrownBy(() -> service.executeQuery("non-existent", Map.of()))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("CRUD操作数据一致性测试")
    class CrudConsistencyTests {

        @Test
        @DisplayName("创建查询 - 应正确保存")
        void create_ShouldPersist() {
            StreamQuery newQuery = StreamQueryBuilder.aStreamQuery()
                    .withDefaults()
                    .build();
            when(mapper.insert(any(StreamQuery.class))).thenReturn(1);

            StreamQuery result = service.create(newQuery);

            assertThat(result).isNotNull();
            verify(mapper, times(1)).insert(newQuery);
        }

        @Test
        @DisplayName("更新查询 - 应正确更新")
        void update_ShouldPersistChanges() {
            when(mapper.updateById(sampleQuery)).thenReturn(1);

            StreamQuery result = service.update(sampleQuery);

            assertThat(result).isNotNull();
            verify(mapper, times(1)).updateById(sampleQuery);
        }

        @Test
        @DisplayName("删除查询 - 应正确删除")
        void delete_ShouldRemove() {
            doNothing().when(mapper).deleteById("test-id");

            service.delete("test-id");

            verify(mapper, times(1)).deleteById("test-id");
        }

        @Test
        @DisplayName("查询存在性检查 - 存在时返回true")
        void exists_WhenExists_ShouldReturnTrue() {
            when(mapper.selectById("existing")).thenReturn(sampleQuery);

            boolean exists = service.exists("existing");

            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("查询存在性检查 - 不存在时返回false")
        void exists_WhenNotExists_ShouldReturnFalse() {
            when(mapper.selectById("non-existent")).thenReturn(null);

            boolean exists = service.exists("non-existent");

            assertThat(exists).isFalse();
        }
    }
}
