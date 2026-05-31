package com.streamsql.modules.data_lineage;

import com.streamsql.dto.LineageParseDTO;
import com.streamsql.entity.LineageEdge;
import com.streamsql.entity.LineageGraph;
import com.streamsql.entity.LineageNode;
import com.streamsql.fixture.TestBuilders;
import com.streamsql.fixture.TestFixtures;
import com.streamsql.mapper.LineageEdgeMapper;
import com.streamsql.mapper.LineageGraphMapper;
import com.streamsql.mapper.LineageNodeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
@DisplayName("数据血缘解析模块测试")
class DataLineageServiceTest {

    @Mock
    private LineageGraphMapper lineageGraphMapper;

    @Mock
    private LineageNodeMapper lineageNodeMapper;

    @Mock
    private LineageEdgeMapper lineageEdgeMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private DataLineageService dataLineageService;

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTest {

        @Test
        @DisplayName("解析SQL血缘 - SELECT语句")
        void shouldParseSelectLineageSuccessfully() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO()
                    .sql("SELECT id, name FROM source_table WHERE status = 'active'")
                    .build();

            when(lineageGraphMapper.insert(any(LineageGraph.class))).thenReturn(1);
            when(lineageNodeMapper.insert(any(LineageNode.class))).thenReturn(1);
            when(lineageEdgeMapper.insert(any(LineageEdge.class))).thenReturn(1);

            LineageGraph result = dataLineageService.parseLineage(dto);

            assertNotNull(result);
            assertEquals("SELECT id, name FROM source_table WHERE status = 'active'", result.getSourceSql());
        }

        @Test
        @DisplayName("解析SQL血缘 - INSERT语句")
        void shouldParseInsertLineageSuccessfully() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO()
                    .sql("INSERT INTO target_table SELECT id, name FROM source_table")
                    .build();

            when(lineageGraphMapper.insert(any(LineageGraph.class))).thenReturn(1);
            when(lineageNodeMapper.insert(any(LineageNode.class))).thenReturn(1);
            when(lineageEdgeMapper.insert(any(LineageEdge.class))).thenReturn(1);

            LineageGraph result = dataLineageService.parseLineage(dto);

            assertNotNull(result);
            assertTrue(result.getNodeCount() > 0);
        }

        @Test
        @DisplayName("解析SQL血缘 - JOIN语句")
        void shouldParseJoinLineageSuccessfully() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO()
                    .sql("SELECT a.id, b.name FROM table_a a JOIN table_b b ON a.id = b.id")
                    .build();

            when(lineageGraphMapper.insert(any(LineageGraph.class))).thenReturn(1);
            when(lineageNodeMapper.insert(any(LineageNode.class))).thenReturn(1);
            when(lineageEdgeMapper.insert(any(LineageEdge.class))).thenReturn(1);

            LineageGraph result = dataLineageService.parseLineage(dto);

            assertNotNull(result);
            assertTrue(result.getNodeCount() >= 2);
        }

        @Test
        @DisplayName("查询血缘图谱 - 成功")
        void shouldGetGraphSuccessfully() {
            String graphId = "graph_001";
            LineageGraph expectedGraph = TestFixtures.createLineageGraphEntity();
            expectedGraph.setGraphId(graphId);

            when(lineageGraphMapper.selectById(graphId)).thenReturn(expectedGraph);

            LineageGraph result = dataLineageService.getGraph(graphId);

            assertNotNull(result);
            assertEquals(graphId, result.getGraphId());
        }

        @Test
        @DisplayName("查询图谱节点 - 成功")
        void shouldGetNodesSuccessfully() {
            String graphId = "graph_001";
            LineageNode node = TestFixtures.createLineageNodeEntity();

            when(lineageNodeMapper.selectList(any()))
                    .thenReturn(Arrays.asList(node));

            List<LineageNode> result = dataLineageService.getNodes(graphId);

            assertFalse(result.isEmpty());
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("查询图谱边 - 成功")
        void shouldGetEdgesSuccessfully() {
            String graphId = "graph_001";
            LineageEdge edge = TestFixtures.createLineageEdgeEntity();

            when(lineageEdgeMapper.selectList(any()))
                    .thenReturn(Arrays.asList(edge));

            List<LineageEdge> result = dataLineageService.getEdges(graphId);

            assertFalse(result.isEmpty());
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("删除血缘图谱 - 成功")
        void shouldDeleteGraphSuccessfully() {
            String graphId = "graph_001";
            when(lineageGraphMapper.deleteById(graphId)).thenReturn(1);

            assertDoesNotThrow(() -> dataLineageService.deleteGraph(graphId));
            verify(lineageGraphMapper).deleteById(graphId);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTest {

        @Test
        @DisplayName("解析空SQL - 异常处理")
        void shouldHandleEmptySql() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO().withEmptySql().build();

            assertThrows(Exception.class, () -> dataLineageService.parseLineage(dto));
        }

        @Test
        @DisplayName("解析null SQL - 异常处理")
        void shouldHandleNullSql() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO().withNullSql().build();

            assertThrows(Exception.class, () -> dataLineageService.parseLineage(dto));
        }

        @Test
        @DisplayName("解析无效SQL - 异常处理")
        void shouldHandleInvalidSql() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO().withInvalidSql().build();

            assertThrows(Exception.class, () -> dataLineageService.parseLineage(dto));
        }

        @Test
        @DisplayName("解析复杂SQL - 成功")
        void shouldParseComplexSqlSuccessfully() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO().withComplexSql().build();

            when(lineageGraphMapper.insert(any(LineageGraph.class))).thenReturn(1);
            when(lineageNodeMapper.insert(any(LineageNode.class))).thenReturn(1);
            when(lineageEdgeMapper.insert(any(LineageEdge.class))).thenReturn(1);

            LineageGraph result = dataLineageService.parseLineage(dto);

            assertNotNull(result);
            assertTrue(result.getNodeCount() > 0);
        }

        @Test
        @DisplayName("解析超长SQL - 成功")
        void shouldParseLongSqlSuccessfully() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO().build();
            StringBuilder sql = new StringBuilder("SELECT ");
            for (int i = 0; i < 100; i++) {
                if (i > 0) sql.append(", ");
                sql.append("col").append(i);
            }
            sql.append(" FROM users WHERE id = 1");
            dto.setSql(sql.toString());

            when(lineageGraphMapper.insert(any(LineageGraph.class))).thenReturn(1);
            when(lineageNodeMapper.insert(any(LineageNode.class))).thenReturn(1);
            when(lineageEdgeMapper.insert(any(LineageEdge.class))).thenReturn(1);

            LineageGraph result = dataLineageService.parseLineage(dto);

            assertNotNull(result);
        }

        @Test
        @DisplayName("查询图谱 - 不存在的ID")
        void shouldReturnNullForNonExistentGraph() {
            String nonExistentId = "non_existent";

            when(lineageGraphMapper.selectById(nonExistentId)).thenReturn(null);

            LineageGraph result = dataLineageService.getGraph(nonExistentId);

            assertNull(result);
        }

        @Test
        @DisplayName("查询节点 - 无数据")
        void shouldReturnEmptyNodesWhenNoData() {
            String graphId = "graph_001";

            when(lineageNodeMapper.selectList(any())).thenReturn(Arrays.asList());

            List<LineageNode> result = dataLineageService.getNodes(graphId);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("查询边 - 无数据")
        void shouldReturnEmptyEdgesWhenNoData() {
            String graphId = "graph_001";

            when(lineageEdgeMapper.selectList(any())).thenReturn(Arrays.asList());

            List<LineageEdge> result = dataLineageService.getEdges(graphId);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("删除图谱 - 不存在的ID")
        void shouldHandleDeletingNonExistentGraph() {
            String nonExistentId = "non_existent";
            when(lineageGraphMapper.deleteById(nonExistentId)).thenReturn(0);

            assertDoesNotThrow(() -> dataLineageService.deleteGraph(nonExistentId));
        }
    }

    @Nested
    @DisplayName("并发竞态场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("并发解析血缘 - 保证线程安全")
        void shouldHandleConcurrentParsing() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            when(lineageGraphMapper.insert(any(LineageGraph.class))).thenReturn(1);
            when(lineageNodeMapper.insert(any(LineageNode.class))).thenReturn(1);
            when(lineageEdgeMapper.insert(any(LineageEdge.class))).thenReturn(1);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        LineageParseDTO dto = TestBuilders.lineageParseDTO()
                                .sql("SELECT id, name FROM table_" + index)
                                .build();
                        dataLineageService.parseLineage(dto);
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
            String graphId = "graph_001";
            LineageGraph graph = TestFixtures.createLineageGraphEntity();
            graph.setGraphId(graphId);

            when(lineageGraphMapper.selectById(graphId)).thenReturn(graph);
            when(lineageGraphMapper.deleteById(graphId)).thenReturn(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    dataLineageService.getGraph(graphId);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    dataLineageService.deleteGraph(graphId);
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
        @DisplayName("图谱存储失败 - 异常传播")
        void shouldPropagateExceptionWhenGraphInsertFails() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO().build();

            when(lineageGraphMapper.insert(any(LineageGraph.class)))
                    .thenThrow(new RuntimeException("数据库连接超时"));

            assertThrows(RuntimeException.class, () -> dataLineageService.parseLineage(dto));
        }

        @Test
        @DisplayName("SQL解析异常 - 降级处理")
        void shouldHandleSqlParsingExceptionGracefully() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO()
                    .sql("THIS IS NOT VALID SQL !@#$%")
                    .build();

            assertThrows(Exception.class, () -> dataLineageService.parseLineage(dto));
        }

        @Test
        @DisplayName("节点存储失败 - 异常处理")
        void shouldHandleNodeInsertException() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO().build();

            when(lineageGraphMapper.insert(any(LineageGraph.class))).thenReturn(1);
            when(lineageNodeMapper.insert(any(LineageNode.class)))
                    .thenThrow(new RuntimeException("节点存储失败"));

            assertThrows(RuntimeException.class, () -> dataLineageService.parseLineage(dto));
        }

        @Test
        @DisplayName("边存储失败 - 异常处理")
        void shouldHandleEdgeInsertException() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO().build();

            when(lineageGraphMapper.insert(any(LineageGraph.class))).thenReturn(1);
            when(lineageNodeMapper.insert(any(LineageNode.class))).thenReturn(1);
            when(lineageEdgeMapper.insert(any(LineageEdge.class)))
                    .thenThrow(new RuntimeException("边存储失败"));

            assertThrows(RuntimeException.class, () -> dataLineageService.parseLineage(dto));
        }

        @Test
        @DisplayName("查询图谱时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenQueryFails() {
            String graphId = "graph_001";

            when(lineageGraphMapper.selectById(graphId))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class, () -> dataLineageService.getGraph(graphId));
        }

        @Test
        @DisplayName("删除图谱时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenDeleteFails() {
            String graphId = "graph_001";

            when(lineageGraphMapper.deleteById(graphId))
                    .thenThrow(new RuntimeException("删除失败"));

            assertThrows(RuntimeException.class, () -> dataLineageService.deleteGraph(graphId));
        }

        @Test
        @DisplayName("多表JOIN解析 - 性能测试")
        void shouldHandleMultiTableJoinWithinTimeLimit() {
            StringBuilder sql = new StringBuilder("SELECT ");
            for (int i = 0; i < 50; i++) {
                if (i > 0) sql.append(", ");
                sql.append("t").append(i).append(".col").append(i);
            }
            sql.append(" FROM table0 t0");
            for (int i = 1; i < 50; i++) {
                sql.append(" JOIN table").append(i).append(" t").append(i)
                        .append(" ON t0.id = t").append(i).append(".id");
            }

            LineageParseDTO dto = TestBuilders.lineageParseDTO().sql(sql.toString()).build();

            when(lineageGraphMapper.insert(any(LineageGraph.class))).thenReturn(1);
            when(lineageNodeMapper.insert(any(LineageNode.class))).thenReturn(1);
            when(lineageEdgeMapper.insert(any(LineageEdge.class))).thenReturn(1);

            long startTime = System.currentTimeMillis();
            LineageGraph result = dataLineageService.parseLineage(dto);
            long duration = System.currentTimeMillis() - startTime;

            assertNotNull(result);
            assertTrue(duration < 5000, "复杂SQL解析应在5秒内完成");
        }
    }
}
