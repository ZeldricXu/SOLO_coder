package com.assetinventory.service;

import com.assetinventory.builder.TestDataBuilder;
import com.assetinventory.entity.Asset;
import com.assetinventory.entity.InventoryDifference;
import com.assetinventory.entity.InventoryPerson;
import com.assetinventory.entity.InventoryRecord;
import com.assetinventory.entity.InventoryTask;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.InventoryRecordRepository;
import com.assetinventory.util.AsyncDifferenceDetector;
import com.assetinventory.util.AsyncDifferenceDetector.DetectionResult;
import com.assetinventory.util.AsyncDifferenceDetector.DetectionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("盘点模块单元测试 - 差异识别异步化")
class CountingServiceTest {

    @Mock
    private InventoryRecordRepository recordRepository;

    @Mock
    private TaskService taskService;

    @Mock
    private AssetService assetService;

    @Mock
    private DifferenceService differenceService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private CountingService countingService;

    private AsyncDifferenceDetector asyncDetector;

    private InventoryTask testTask;
    private InventoryPerson testPerson;
    private Asset testAsset;
    private InventoryRecord testRecord;

    @BeforeEach
    void setUp() {
        asyncDetector = new AsyncDifferenceDetector(assetService, differenceService);
        testPerson = TestDataBuilder.personBuilder().buildActivePerson();
        testTask = TestDataBuilder.taskBuilder()
                .assignedPerson(testPerson.getPersonId())
                .buildAssignedTask(testPerson.getPersonId());
        testAsset = TestDataBuilder.assetBuilder()
                .assetQuantity(100)
                .assetLocation("A栋1楼")
                .buildUncountedAsset();
        testRecord = TestDataBuilder.recordBuilder()
                .taskId(testTask.getTaskId())
                .assetId(testAsset.getAssetId())
                .countPerson(testPerson.getPersonId())
                .build();
    }

    @AfterEach
    void tearDown() {
        if (asyncDetector != null && asyncDetector.isRunning()) {
            asyncDetector.stop();
        }
    }

    @Test
    @DisplayName("测试盘点执行完成后立即返回响应 - 不阻塞主线程")
    void testExecuteCounting_ImmediateResponse_NoBlocking() {
        doNothing().when(taskService).validateTaskPendingOrAssigned(anyString());
        when(taskService.getTaskByIdOrThrow(anyString())).thenReturn(testTask);
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(recordRepository.save(any(InventoryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordCountingHistory(anyString(), anyString(), anyString());

        long startTime = System.currentTimeMillis();

        InventoryRecord result = countingService.executeCounting(
                testTask.getTaskId(),
                testAsset.getAssetId(),
                100,
                "A栋1楼"
        );

        long responseTime = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue(responseTime < 1000, "响应时间应小于1秒，实际: " + responseTime + "ms");
        verify(recordRepository, times(1)).save(any(InventoryRecord.class));
    }

    @Test
    @DisplayName("测试正常盘点 - 无差异")
    void testExecuteCounting_Normal_NoDifference() {
        doNothing().when(taskService).validateTaskPendingOrAssigned(anyString());
        when(taskService.getTaskByIdOrThrow(anyString())).thenReturn(testTask);
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(recordRepository.save(any(InventoryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordCountingHistory(anyString(), anyString(), anyString());

        InventoryRecord result = countingService.executeCounting(
                testTask.getTaskId(),
                testAsset.getAssetId(),
                100,
                "A栋1楼"
        );

        assertEquals("normal", result.getCountStatus());
        assertEquals(100, result.getCountQuantity());
        assertEquals("A栋1楼", result.getCountLocation());

        verify(assetService, times(1)).updateAssetStatus(testAsset.getAssetId(), "counted");
        verify(assetService, times(1)).updateLastCountedAt(eq(testAsset.getAssetId()), any());
        verify(differenceService, never()).createDifference(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("测试数量差异盘点 - 创建数量差异记录")
    void testExecuteCounting_QuantityDifference_CreatesDifference() {
        doNothing().when(taskService).validateTaskPendingOrAssigned(anyString());
        when(taskService.getTaskByIdOrThrow(anyString())).thenReturn(testTask);
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(recordRepository.save(any(InventoryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(differenceService.createDifference(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(TestDataBuilder.differenceBuilder().buildQuantityDiff(100, 95));
        doNothing().when(historyService).recordCountingHistory(anyString(), anyString(), anyString());

        InventoryRecord result = countingService.executeCounting(
                testTask.getTaskId(),
                testAsset.getAssetId(),
                95,
                "A栋1楼"
        );

        assertEquals("difference", result.getCountStatus());
        assertEquals(95, result.getCountQuantity());

        verify(differenceService, times(1)).createDifference(
                anyString(),
                eq(testAsset.getAssetId()),
                eq("quantity"),
                eq(100),
                eq(95)
        );
        verify(assetService, never()).updateAssetStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("测试位置差异盘点 - 创建位置差异记录")
    void testExecuteCounting_LocationDifference_CreatesDifference() {
        doNothing().when(taskService).validateTaskPendingOrAssigned(anyString());
        when(taskService.getTaskByIdOrThrow(anyString())).thenReturn(testTask);
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(recordRepository.save(any(InventoryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(differenceService.createDifference(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(TestDataBuilder.differenceBuilder().buildLocationDiff());
        doNothing().when(historyService).recordCountingHistory(anyString(), anyString(), anyString());

        InventoryRecord result = countingService.executeCounting(
                testTask.getTaskId(),
                testAsset.getAssetId(),
                100,
                "B栋2楼"
        );

        assertEquals("difference", result.getCountStatus());

        verify(differenceService, times(1)).createDifference(
                anyString(),
                eq(testAsset.getAssetId()),
                eq("location"),
                eq(100),
                eq(100)
        );
    }

    @Test
    @DisplayName("测试双重差异 - 数量和位置都不同")
    void testExecuteCounting_DoubleDifference_BothQuantityAndLocation() {
        doNothing().when(taskService).validateTaskPendingOrAssigned(anyString());
        when(taskService.getTaskByIdOrThrow(anyString())).thenReturn(testTask);
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(recordRepository.save(any(InventoryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(differenceService.createDifference(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(TestDataBuilder.differenceBuilder().buildQuantityDiff(100, 95));
        doNothing().when(historyService).recordCountingHistory(anyString(), anyString(), anyString());

        InventoryRecord result = countingService.executeCounting(
                testTask.getTaskId(),
                testAsset.getAssetId(),
                95,
                "B栋2楼"
        );

        assertEquals("difference", result.getCountStatus());

        verify(differenceService, times(2)).createDifference(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("测试盘点任务已完成 - 拒绝执行")
    void testExecuteCounting_TaskCompleted_Rejected() {
        doThrow(new InventoryException(400, "任务已完成，无法执行"))
                .when(taskService).validateTaskPendingOrAssigned(anyString());

        InventoryException exception = assertThrows(InventoryException.class,
                () -> countingService.executeCounting(
                        testTask.getTaskId(),
                        testAsset.getAssetId(),
                        100,
                        "A栋1楼"
                ));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("已完成"));
        verify(recordRepository, never()).save(any(InventoryRecord.class));
    }

    @Test
    @DisplayName("测试资产不存在 - 拒绝盘点")
    void testExecuteCounting_AssetNotFound_Rejected() {
        doNothing().when(taskService).validateTaskPendingOrAssigned(anyString());
        when(taskService.getTaskByIdOrThrow(anyString())).thenReturn(testTask);
        when(assetService.getAssetByIdOrThrow(anyString()))
                .thenThrow(new InventoryException(404, "资产不存在"));

        InventoryException exception = assertThrows(InventoryException.class,
                () -> countingService.executeCounting(
                        testTask.getTaskId(),
                        "nonexistent_asset",
                        100,
                        "A栋1楼"
                ));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("资产不存在"));
    }

    @Test
    @DisplayName("测试异步差异识别 - 后台Worker执行")
    void testAsyncDetection_BackgroundWorkerExecutes() throws InterruptedException {
        asyncDetector.start();
        assertTrue(asyncDetector.isRunning());

        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(differenceService.createDifference(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(TestDataBuilder.differenceBuilder().buildQuantityDiff(100, 95));

        InventoryRecord diffRecord = TestDataBuilder.recordBuilder()
                .countQuantity(95)
                .countLocation("A栋1楼")
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DetectionResult> resultRef = new AtomicReference<>();

        asyncDetector.submitDetection(diffRecord, 3, result -> {
            resultRef.set(result);
            latch.countDown();
        });

        boolean completed = latch.await(5, TimeUnit.SECONDS);

        assertTrue(completed, "异步检测应在5秒内完成");
        DetectionResult result = resultRef.get();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertTrue(result.hasDifferences());
    }

    @Test
    @DisplayName("测试异步差异识别失败 - 重试机制")
    void testAsyncDetection_Failure_RetryMechanism() throws InterruptedException {
        asyncDetector.start();

        AtomicInteger attemptCount = new AtomicInteger(0);
        when(assetService.getAssetByIdOrThrow(anyString()))
                .thenAnswer(invocation -> {
                    int attempt = attemptCount.incrementAndGet();
                    if (attempt < 3) {
                        throw new RuntimeException("模拟失败，尝试次数: " + attempt);
                    }
                    return testAsset;
                });

        when(differenceService.createDifference(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(TestDataBuilder.differenceBuilder().buildQuantityDiff(100, 95));

        InventoryRecord diffRecord = TestDataBuilder.recordBuilder()
                .countQuantity(95)
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DetectionResult> resultRef = new AtomicReference<>();

        asyncDetector.submitDetection(diffRecord, 3, result -> {
            resultRef.set(result);
            latch.countDown();
        });

        boolean completed = latch.await(10, TimeUnit.SECONDS);

        assertTrue(completed, "异步检测应在10秒内完成（包含重试）");
        DetectionResult result = resultRef.get();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(2, result.getRetryAttempts(), "应在第3次尝试成功");
        assertTrue(asyncDetector.getRetryCount() >= 2);
    }

    @Test
    @DisplayName("测试异步差异识别 - 最大重试次数后失败")
    void testAsyncDetection_MaxRetriesExceeded_Fails() throws InterruptedException {
        asyncDetector.start();

        when(assetService.getAssetByIdOrThrow(anyString()))
                .thenThrow(new RuntimeException("持续失败"));

        InventoryRecord diffRecord = TestDataBuilder.recordBuilder()
                .countQuantity(95)
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DetectionResult> resultRef = new AtomicReference<>();

        asyncDetector.submitDetection(diffRecord, 2, result -> {
            resultRef.set(result);
            latch.countDown();
        });

        boolean completed = latch.await(10, TimeUnit.SECONDS);

        assertTrue(completed);
        DetectionResult result = resultRef.get();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(2, result.getRetryAttempts());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("测试异步检测器启动和停止")
    void testAsyncDetector_StartAndStop() {
        assertFalse(asyncDetector.isRunning());

        asyncDetector.start();
        assertTrue(asyncDetector.isRunning());

        asyncDetector.stop();
        assertFalse(asyncDetector.isRunning());
    }

    @Test
    @DisplayName("测试异步检测器队列管理")
    void testAsyncDetector_QueueManagement() {
        asyncDetector.start();

        InventoryRecord record1 = TestDataBuilder.recordBuilder().countId("count_001").build();
        InventoryRecord record2 = TestDataBuilder.recordBuilder().countId("count_002").build();
        InventoryRecord record3 = TestDataBuilder.recordBuilder().countId("count_003").build();

        assertEquals(0, asyncDetector.getQueueSize());

        asyncDetector.submitDetection(record1);
        asyncDetector.submitDetection(record2);
        asyncDetector.submitDetection(record3);

        assertTrue(asyncDetector.getQueueSize() <= 3);
    }

    @Test
    @DisplayName("测试异步检测正常情况 - 无差异")
    void testAsyncDetection_NormalCase_NoDifferences() throws InterruptedException {
        asyncDetector.start();

        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);

        InventoryRecord normalRecord = TestDataBuilder.recordBuilder()
                .countQuantity(100)
                .countLocation("A栋1楼")
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DetectionResult> resultRef = new AtomicReference<>();

        asyncDetector.submitDetection(normalRecord, 1, result -> {
            resultRef.set(result);
            latch.countDown();
        });

        boolean completed = latch.await(5, TimeUnit.SECONDS);

        assertTrue(completed);
        DetectionResult result = resultRef.get();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertFalse(result.hasDifferences());
    }

    @Test
    @DisplayName("测试批量异步检测 - 多个任务并行处理")
    void testAsyncDetection_BatchProcessing_MultipleTasks() throws InterruptedException {
        asyncDetector.start();

        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(differenceService.createDifference(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(TestDataBuilder.differenceBuilder().buildQuantityDiff(100, 95));

        int taskCount = 5;
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            InventoryRecord record = TestDataBuilder.recordBuilder()
                    .countId("count_" + i)
                    .countQuantity(95 + i)
                    .build();

            asyncDetector.submitDetection(record, 1, result -> latch.countDown());
        }

        boolean allCompleted = latch.await(30, TimeUnit.SECONDS);

        assertTrue(allCompleted, "所有异步检测应在30秒内完成");
        assertTrue(asyncDetector.getCompletedCount() >= taskCount);
    }

    @Test
    @DisplayName("测试异步检测器重置功能")
    void testAsyncDetector_ResetFunction() {
        asyncDetector.start();

        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);

        InventoryRecord record = TestDataBuilder.recordBuilder().build();
        asyncDetector.submitDetection(record);

        asyncDetector.reset();

        assertEquals(0, asyncDetector.getCompletedCount());
        assertEquals(0, asyncDetector.getRetryCount());
        assertEquals(0, asyncDetector.getQueueSize());
    }

    @Test
    @DisplayName("测试盘点执行时使用实际盘点位置")
    void testExecuteCounting_UsesActualLocation() {
        doNothing().when(taskService).validateTaskPendingOrAssigned(anyString());
        when(taskService.getTaskByIdOrThrow(anyString())).thenReturn(testTask);
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(recordRepository.save(any(InventoryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordCountingHistory(anyString(), anyString(), anyString());

        String actualLocation = "C栋3楼-实际盘点位置";

        InventoryRecord result = countingService.executeCounting(
                testTask.getTaskId(),
                testAsset.getAssetId(),
                100,
                actualLocation
        );

        assertEquals(actualLocation, result.getCountLocation());
    }

    @Test
    @DisplayName("测试盘点执行时未指定位置 - 使用资产位置")
    void testExecuteCounting_NoLocationProvided_UsesAssetLocation() {
        doNothing().when(taskService).validateTaskPendingOrAssigned(anyString());
        when(taskService.getTaskByIdOrThrow(anyString())).thenReturn(testTask);
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(recordRepository.save(any(InventoryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordCountingHistory(anyString(), anyString(), anyString());

        InventoryRecord result = countingService.executeCounting(
                testTask.getTaskId(),
                testAsset.getAssetId(),
                100,
                null
        );

        assertEquals(testAsset.getAssetLocation(), result.getCountLocation());
    }

    @Test
    @DisplayName("测试按任务ID获取盘点记录")
    void testGetRecordsByTaskId() {
        java.util.List<InventoryRecord> records = TestDataBuilder.recordBuilder().buildMultiple(3, "normal");
        when(recordRepository.findByTaskId(anyString())).thenReturn(records);

        java.util.List<InventoryRecord> result = countingService.getRecordsByTaskId(testTask.getTaskId());

        assertEquals(3, result.size());
        verify(recordRepository, times(1)).findByTaskId(testTask.getTaskId());
    }

    @Test
    @DisplayName("测试按资产ID获取盘点记录")
    void testGetRecordsByAssetId() {
        java.util.List<InventoryRecord> records = TestDataBuilder.recordBuilder().buildMultiple(5, "normal");
        when(recordRepository.findByAssetId(anyString())).thenReturn(records);

        java.util.List<InventoryRecord> result = countingService.getRecordsByAssetId(testAsset.getAssetId());

        assertEquals(5, result.size());
    }

    @Test
    @DisplayName("测试按状态获取盘点记录")
    void testGetRecordsByStatus() {
        java.util.List<InventoryRecord> normalRecords = TestDataBuilder.recordBuilder().buildMultiple(10, "normal");
        java.util.List<InventoryRecord> diffRecords = TestDataBuilder.recordBuilder().buildMultiple(3, "difference");

        when(recordRepository.findByCountStatus("normal")).thenReturn(normalRecords);
        when(recordRepository.findByCountStatus("difference")).thenReturn(diffRecords);

        assertEquals(10, countingService.getRecordsByStatus("normal").size());
        assertEquals(3, countingService.getRecordsByStatus("difference").size());
    }
}
