package com.assetmanage.service;

import com.assetmanage.dto.InventoryDiffHandleRequest;
import com.assetmanage.entity.InventoryCheck;
import com.assetmanage.entity.InventoryDifference;
import com.assetmanage.testdata.TestDataBuilder;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryAsyncServiceTest {

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private InventoryAsyncService inventoryAsyncService;

    private InventoryCheck testCheck;
    private List<InventoryDifference> testDiffs;
    private String operatorId;

    @BeforeEach
    void setUp() {
        testCheck = TestDataBuilder.buildInProgressInventoryCheck();
        testDiffs = TestDataBuilder.buildMultipleDifferences();
        operatorId = TestDataBuilder.TEST_OPERATOR_ID;
    }

    @Test
    @DisplayName("测试提交异步处理后立即返回任务ID")
    void testSubmitAsyncProcessingReturnsImmediately() {
        long startTime = System.currentTimeMillis();

        List<String> diffIds = Arrays.asList(
                testDiffs.get(0).getDiffId(),
                testDiffs.get(1).getDiffId()
        );

        String taskId = inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                diffIds,
                operatorId
        );

        long elapsedTime = System.currentTimeMillis() - startTime;

        assertNotNull(taskId, "应该立即返回任务ID");
        assertTrue(elapsedTime < 1000, "应该在1秒内返回，验证立即响应");
        assertTrue(taskId.startsWith("task_"), "任务ID应该以task_开头");
    }

    @Test
    @DisplayName("测试提交异步处理后后台Worker执行差异处理")
    void testBackgroundWorkerExecutesDiffHandling() {
        List<String> diffIds = Arrays.asList(
                testDiffs.get(0).getDiffId(),
                testDiffs.get(1).getDiffId()
        );

        String taskId = inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                diffIds,
                operatorId
        );

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            InventoryAsyncService.TaskStatus status = inventoryAsyncService.getTaskStatus(taskId);
            assertNotNull(status);
            assertTrue(status.isCompleted() || status.getSuccess() > 0);
        });

        verify(inventoryService, atLeastOnce()).handleDifference(any(InventoryDiffHandleRequest.class));
    }

    @Test
    @DisplayName("测试任务状态跟踪 - 成功处理所有差异")
    void testTaskStatusTrackingAllSuccess() {
        List<String> diffIds = Arrays.asList(
                testDiffs.get(0).getDiffId()
        );

        doNothing().when(inventoryService).handleDifference(any(InventoryDiffHandleRequest.class));

        String taskId = inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                diffIds,
                operatorId
        );

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            InventoryAsyncService.TaskStatus status = inventoryAsyncService.getTaskStatus(taskId);
            assertNotNull(status);
            assertTrue(status.isCompleted(), "任务应该完成");
            assertEquals(1, status.getTotal(), "总任务数应该是1");
            assertEquals(1, status.getSuccess(), "成功数应该是1");
            assertEquals(0, status.getFailed(), "失败数应该是0");
            assertEquals(0, status.getPending(), "待处理数应该是0");
        });
    }

    @Test
    @DisplayName("测试处理失败时的重试机制 - 最多3次重试")
    void testRetryMechanismOnFailure() {
        String failingDiffId = "failing_diff_" + System.currentTimeMillis();
        List<String> diffIds = Arrays.asList(failingDiffId);

        doThrow(new RuntimeException("模拟处理失败"))
                .when(inventoryService).handleDifference(any(InventoryDiffHandleRequest.class));

        String taskId = inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                diffIds,
                operatorId
        );

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            InventoryAsyncService.TaskStatus status = inventoryAsyncService.getTaskStatus(taskId);
            assertNotNull(status);
            assertTrue(status.isCompleted());
        });

        InventoryAsyncService.TaskStatus finalStatus = inventoryAsyncService.getTaskStatus(taskId);
        assertEquals(1, finalStatus.getFailed(), "最终应该标记为失败");
        assertEquals(1, finalStatus.getFailedDiffs().size(), "应该记录失败的差异ID");

        verify(inventoryService, times(3))
                .handleDifference(argThat(req -> req.getDiffId().equals(failingDiffId)));
    }

    @Test
    @DisplayName("测试部分成功部分失败的场景")
    void testPartialSuccessAndPartialFailure() {
        String successDiffId = "success_diff_" + System.currentTimeMillis();
        String failingDiffId = "failing_diff_" + System.currentTimeMillis();
        List<String> diffIds = Arrays.asList(successDiffId, failingDiffId);

        doAnswer(invocation -> {
            InventoryDiffHandleRequest req = invocation.getArgument(0);
            if (successDiffId.equals(req.getDiffId())) {
                return null;
            }
            throw new RuntimeException("处理失败");
        }).when(inventoryService).handleDifference(any(InventoryDiffHandleRequest.class));

        String taskId = inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                diffIds,
                operatorId
        );

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            InventoryAsyncService.TaskStatus status = inventoryAsyncService.getTaskStatus(taskId);
            assertNotNull(status);
            assertTrue(status.isCompleted());
        });

        InventoryAsyncService.TaskStatus finalStatus = inventoryAsyncService.getTaskStatus(taskId);
        assertEquals(2, finalStatus.getTotal());
        assertEquals(1, finalStatus.getSuccess());
        assertEquals(1, finalStatus.getFailed());
        assertEquals(0, finalStatus.getPending());
        assertTrue(finalStatus.getFailedDiffs().contains(failingDiffId));
        assertFalse(finalStatus.getFailedDiffs().contains(successDiffId));
    }

    @Test
    @DisplayName("测试第一次失败后重试成功的场景")
    void testRetrySuccessAfterInitialFailure() {
        String retrySuccessDiffId = "retry_success_" + System.currentTimeMillis();
        List<String> diffIds = Arrays.asList(retrySuccessDiffId);
        int[] attemptCount = {0};

        doAnswer(invocation -> {
            attemptCount[0]++;
            if (attemptCount[0] < 2) {
                throw new RuntimeException("前两次失败");
            }
            return null;
        }).when(inventoryService).handleDifference(any(InventoryDiffHandleRequest.class));

        String taskId = inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                diffIds,
                operatorId
        );

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            InventoryAsyncService.TaskStatus status = inventoryAsyncService.getTaskStatus(taskId);
            assertNotNull(status);
            assertTrue(status.isCompleted());
        });

        InventoryAsyncService.TaskStatus finalStatus = inventoryAsyncService.getTaskStatus(taskId);
        assertEquals(1, finalStatus.getSuccess(), "重试后应该成功");
        assertEquals(0, finalStatus.getFailed());
        assertTrue(attemptCount[0] >= 2, "应该至少重试了2次");
    }

    @Test
    @DisplayName("测试空差异列表提交")
    void testSubmitWithEmptyDiffList() {
        List<String> emptyDiffIds = Arrays.asList();

        String taskId = inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                emptyDiffIds,
                operatorId
        );

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            InventoryAsyncService.TaskStatus status = inventoryAsyncService.getTaskStatus(taskId);
            assertNotNull(status);
            assertTrue(status.isCompleted());
        });

        InventoryAsyncService.TaskStatus finalStatus = inventoryAsyncService.getTaskStatus(taskId);
        assertEquals(0, finalStatus.getTotal());
        assertEquals(0, finalStatus.getSuccess());
        assertEquals(0, finalStatus.getFailed());
        assertEquals(0, finalStatus.getPending());
    }

    @Test
    @DisplayName("测试多个差异的并行处理")
    void testMultipleDiffsParallelProcessing() {
        int diffCount = 5;
        List<String> diffIds = java.util.stream.IntStream.range(0, diffCount)
                .mapToObj(i -> "diff_" + i + "_" + System.currentTimeMillis())
                .collect(java.util.stream.Collectors.toList());

        doAnswer(invocation -> {
            Thread.sleep(100);
            return null;
        }).when(inventoryService).handleDifference(any(InventoryDiffHandleRequest.class));

        String taskId = inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                diffIds,
                operatorId
        );

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            InventoryAsyncService.TaskStatus status = inventoryAsyncService.getTaskStatus(taskId);
            assertNotNull(status);
            assertTrue(status.isCompleted());
        });

        InventoryAsyncService.TaskStatus finalStatus = inventoryAsyncService.getTaskStatus(taskId);
        assertEquals(diffCount, finalStatus.getTotal());
        assertEquals(diffCount, finalStatus.getSuccess());
        assertEquals(0, finalStatus.getFailed());

        verify(inventoryService, times(diffCount)).handleDifference(any(InventoryDiffHandleRequest.class));
    }

    @Test
    @DisplayName("测试获取不存在的任务状态返回null")
    void testGetNonExistentTaskStatusReturnsNull() {
        InventoryAsyncService.TaskStatus status = inventoryAsyncService.getTaskStatus("non_existent_task");
        assertNull(status);
    }

    @Test
    @DisplayName("测试异步处理时正确传递操作员ID")
    void testOperatorIdPassedCorrectlyInAsync() {
        List<String> diffIds = Arrays.asList(testDiffs.get(0).getDiffId());

        doNothing().when(inventoryService).handleDifference(any(InventoryDiffHandleRequest.class));

        inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                diffIds,
                operatorId
        );

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(inventoryService).handleDifference(argThat(req ->
                    operatorId.equals(req.getOperatorId())
            ));
        });
    }

    @Test
    @DisplayName("测试任务状态统计准确性")
    void testTaskStatusStatisticsAccuracy() {
        List<String> diffIds = Arrays.asList(
                "diff_1_" + System.currentTimeMillis(),
                "diff_2_" + System.currentTimeMillis(),
                "diff_3_" + System.currentTimeMillis()
        );

        String taskId = inventoryAsyncService.submitAsyncProcessing(
                testCheck.getCheckId(),
                diffIds,
                operatorId
        );

        InventoryAsyncService.TaskStatus initialStatus = inventoryAsyncService.getTaskStatus(taskId);
        assertEquals(3, initialStatus.getTotal());
        assertEquals(3, initialStatus.getPending());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            InventoryAsyncService.TaskStatus status = inventoryAsyncService.getTaskStatus(taskId);
            assertEquals(3, status.getSuccess() + status.getFailed() + status.getPending());
        });
    }
}
