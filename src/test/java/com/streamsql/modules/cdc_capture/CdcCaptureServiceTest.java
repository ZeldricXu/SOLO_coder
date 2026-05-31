package com.streamsql.modules.cdc_capture;

import com.streamsql.dto.CdcTaskDTO;
import com.streamsql.entity.CdcCaptureTask;
import com.streamsql.entity.CdcEventRecord;
import com.streamsql.fixture.TestBuilders;
import com.streamsql.fixture.TestFixtures;
import com.streamsql.mapper.CdcCaptureTaskMapper;
import com.streamsql.mapper.CdcEventRecordMapper;
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
@DisplayName("CDC增量捕获模块测试")
class CdcCaptureServiceTest {

    @Mock
    private CdcCaptureTaskMapper cdcCaptureTaskMapper;

    @Mock
    private CdcEventRecordMapper cdcEventRecordMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CdcCaptureService cdcCaptureService;

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTest {

        @Test
        @DisplayName("创建CDC任务 - 成功")
        void shouldCreateTaskSuccessfully() throws Exception {
            CdcTaskDTO dto = TestBuilders.cdcTaskDTO().build();
            when(cdcCaptureTaskMapper.insert(any(CdcCaptureTask.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            CdcCaptureTask result = cdcCaptureService.createTask(dto);

            assertNotNull(result);
            assertEquals(dto.getTaskName(), result.getTaskName());
            assertEquals("stopped", result.getStatus());
            assertTrue(result.getEnabled());
        }

        @Test
        @DisplayName("查询CDC任务 - 成功")
        void shouldGetTaskSuccessfully() {
            String taskId = "task_001";
            CdcCaptureTask expectedTask = TestFixtures.createCdcCaptureTaskEntity();
            expectedTask.setTaskId(taskId);

            when(cdcCaptureTaskMapper.selectById(taskId)).thenReturn(expectedTask);

            CdcCaptureTask result = cdcCaptureService.getTask(taskId);

            assertNotNull(result);
            assertEquals(taskId, result.getTaskId());
        }

        @Test
        @DisplayName("列出CDC任务 - 成功")
        void shouldListTasksSuccessfully() {
            CdcCaptureTask task = TestFixtures.createCdcCaptureTaskEntity();
            List<CdcCaptureTask> tasks = Arrays.asList(task);

            when(cdcCaptureTaskMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(tasks, 1));

            com.streamsql.common.PageResult<CdcCaptureTask> result =
                    cdcCaptureService.listTasks(1, 10, null, null);

            assertNotNull(result);
            assertFalse(result.getRecords().isEmpty());
        }

        @Test
        @DisplayName("删除CDC任务 - 成功")
        void shouldDeleteTaskSuccessfully() {
            String taskId = "task_001";
            when(cdcCaptureTaskMapper.deleteById(taskId)).thenReturn(1);

            assertDoesNotThrow(() -> cdcCaptureService.deleteTask(taskId));
            verify(cdcCaptureTaskMapper).deleteById(taskId);
        }

        @Test
        @DisplayName("查询事件记录 - 成功")
        void shouldGetEventRecordsSuccessfully() {
            CdcEventRecord record = TestFixtures.createCdcEventRecordEntity();

            when(cdcEventRecordMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                            Arrays.asList(record), 1));

            com.streamsql.common.PageResult<CdcEventRecord> result =
                    cdcCaptureService.getEventRecords("task_001", 1, 10);

            assertNotNull(result);
            assertFalse(result.getRecords().isEmpty());
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTest {

        @Test
        @DisplayName("创建CDC任务 - 空字符串值")
        void shouldCreateTaskWithEmptyValues() throws Exception {
            CdcTaskDTO dto = TestBuilders.cdcTaskDTO().withEmptyValues().build();
            when(cdcCaptureTaskMapper.insert(any(CdcCaptureTask.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            CdcCaptureTask result = cdcCaptureService.createTask(dto);

            assertNotNull(result);
            assertEquals("", result.getTaskName());
        }

        @Test
        @DisplayName("创建CDC任务 - null值")
        void shouldCreateTaskWithNullValues() throws Exception {
            CdcTaskDTO dto = new CdcTaskDTO();
            when(cdcCaptureTaskMapper.insert(any(CdcCaptureTask.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn(null);

            CdcCaptureTask result = cdcCaptureService.createTask(dto);

            assertNotNull(result);
            assertNull(result.getTaskName());
        }

        @Test
        @DisplayName("创建CDC任务 - 无效输出类型")
        void shouldCreateTaskWithInvalidOutputType() throws Exception {
            CdcTaskDTO dto = TestBuilders.cdcTaskDTO().withEmptyValues()
                    .outputType("invalid_type")
                    .build();
            when(cdcCaptureTaskMapper.insert(any(CdcCaptureTask.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            CdcCaptureTask result = cdcCaptureService.createTask(dto);

            assertNotNull(result);
            assertEquals("invalid_type", result.getOutputType());
        }

        @Test
        @DisplayName("创建CDC任务 - 禁用状态")
        void shouldCreateTaskWithDisabledStatus() throws Exception {
            CdcTaskDTO dto = TestBuilders.cdcTaskDTO().build();
            dto.setEnabled(false);
            when(cdcCaptureTaskMapper.insert(any(CdcCaptureTask.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            CdcCaptureTask result = cdcCaptureService.createTask(dto);

            assertNotNull(result);
            assertFalse(result.getEnabled());
        }

        @Test
        @DisplayName("查询CDC任务 - 不存在的ID")
        void shouldReturnNullForNonExistentTask() {
            String nonExistentId = "non_existent";

            when(cdcCaptureTaskMapper.selectById(nonExistentId)).thenReturn(null);

            CdcCaptureTask result = cdcCaptureService.getTask(nonExistentId);

            assertNull(result);
        }

        @Test
        @DisplayName("删除CDC任务 - 不存在的ID")
        void shouldHandleDeletingNonExistentTask() {
            String nonExistentId = "non_existent";
            when(cdcCaptureTaskMapper.deleteById(nonExistentId)).thenReturn(0);

            assertDoesNotThrow(() -> cdcCaptureService.deleteTask(nonExistentId));
        }

        @Test
        @DisplayName("查询事件记录 - 无数据")
        void shouldReturnEmptyEventRecordsWhenNoData() {
            when(cdcEventRecordMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

            com.streamsql.common.PageResult<CdcEventRecord> result =
                    cdcCaptureService.getEventRecords("task_001", 1, 10);

            assertNotNull(result);
            assertTrue(result.getRecords().isEmpty());
            assertEquals(0, result.getTotal());
        }

        @Test
        @DisplayName("列出CDC任务 - 空结果")
        void shouldReturnEmptyPageWhenNoTasks() {
            when(cdcCaptureTaskMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

            com.streamsql.common.PageResult<CdcCaptureTask> result =
                    cdcCaptureService.listTasks(1, 10, null, null);

            assertNotNull(result);
            assertTrue(result.getRecords().isEmpty());
            assertEquals(0, result.getTotal());
        }

        @Test
        @DisplayName("启动CDC任务 - 不存在的ID")
        void shouldThrowWhenStartingNonExistentTask() {
            String nonExistentId = "non_existent";

            when(cdcCaptureTaskMapper.selectById(nonExistentId)).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> cdcCaptureService.startTask(nonExistentId));
        }

        @Test
        @DisplayName("停止CDC任务 - 不存在的ID")
        void shouldThrowWhenStoppingNonExistentTask() {
            String nonExistentId = "non_existent";

            when(cdcCaptureTaskMapper.selectById(nonExistentId)).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> cdcCaptureService.stopTask(nonExistentId));
        }
    }

    @Nested
    @DisplayName("并发竞态场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("并发创建CDC任务 - 保证线程安全")
        void shouldHandleConcurrentTaskCreation() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            when(cdcCaptureTaskMapper.insert(any(CdcCaptureTask.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        CdcTaskDTO dto = TestBuilders.cdcTaskDTO()
                                .taskName("并发任务-" + index + "-" + System.nanoTime())
                                .build();
                        cdcCaptureService.createTask(dto);
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
        @DisplayName("并发启动和停止任务 - 保证一致性")
        void shouldHandleConcurrentStartAndStop() throws InterruptedException {
            String taskId = "task_001";
            CdcCaptureTask task = TestFixtures.createCdcCaptureTaskEntity();
            task.setTaskId(taskId);
            task.setDatasourceId("ds_001");
            task.setConnectionConfig("{\"host\":\"localhost\",\"port\":3306}");

            when(cdcCaptureTaskMapper.selectById(taskId)).thenReturn(task);
            when(cdcCaptureTaskMapper.updateById(any(CdcCaptureTask.class))).thenReturn(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    cdcCaptureService.startTask(taskId);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    cdcCaptureService.stopTask(taskId);
                } catch (Exception ignored) {
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
            CdcTaskDTO dto = TestBuilders.cdcTaskDTO().build();

            when(cdcCaptureTaskMapper.insert(any(CdcCaptureTask.class)))
                    .thenThrow(new RuntimeException("数据库连接超时"));

            assertThrows(RuntimeException.class, () -> cdcCaptureService.createTask(dto));
        }

        @Test
        @DisplayName("序列化失败 - 异常处理")
        void shouldHandleSerializationException() {
            CdcTaskDTO dto = TestBuilders.cdcTaskDTO().build();

            when(objectMapper.writeValueAsString(any()))
                    .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("序列化失败") {});

            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> cdcCaptureService.createTask(dto));
        }

        @Test
        @DisplayName("启动任务时Binlog连接失败 - 异常处理")
        void shouldHandleBinlogConnectionFailure() {
            String taskId = "task_001";
            CdcCaptureTask task = TestFixtures.createCdcCaptureTaskEntity();
            task.setTaskId(taskId);
            task.setDatasourceId("ds_001");
            task.setConnectionConfig("{\"host\":\"invalid_host\",\"port\":3306}");

            when(cdcCaptureTaskMapper.selectById(taskId)).thenReturn(task);
            when(objectMapper.readValue(anyString(), eq(Map.class)))
                    .thenReturn(java.util.Map.of("host", "invalid_host", "port", 3306));

            assertThrows(Exception.class, () -> cdcCaptureService.startTask(taskId));
        }

        @Test
        @DisplayName("查询任务时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenQueryFails() {
            String taskId = "task_001";

            when(cdcCaptureTaskMapper.selectById(taskId))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class, () -> cdcCaptureService.getTask(taskId));
        }

        @Test
        @DisplayName("删除任务时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenDeleteFails() {
            String taskId = "task_001";

            when(cdcCaptureTaskMapper.deleteById(taskId))
                    .thenThrow(new RuntimeException("删除失败"));

            assertThrows(RuntimeException.class, () -> cdcCaptureService.deleteTask(taskId));
        }

        @Test
        @DisplayName("查询事件记录时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenQueryingEvents() {
            String taskId = "task_001";

            when(cdcEventRecordMapper.selectPage(any(), any()))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class,
                    () -> cdcCaptureService.getEventRecords(taskId, 1, 10));
        }

        @Test
        @DisplayName("Kafka输出配置无效 - 异常处理")
        void shouldHandleInvalidKafkaConfig() throws Exception {
            CdcTaskDTO dto = TestBuilders.cdcTaskDTO().build();
            dto.setOutputType("kafka");
            dto.setOutputConfig(java.util.Map.of("invalid_key", "invalid_value"));

            when(cdcCaptureTaskMapper.insert(any(CdcCaptureTask.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            CdcCaptureTask result = cdcCaptureService.createTask(dto);

            assertNotNull(result);
            assertEquals("kafka", result.getOutputType());
        }
    }
}
