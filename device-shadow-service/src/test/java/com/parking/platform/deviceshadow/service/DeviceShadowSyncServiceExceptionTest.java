package com.parking.platform.deviceshadow.service;

import com.parking.platform.common.entity.DeviceShadowEntity;
import com.parking.platform.common.exception.DeviceShadowSyncException;
import com.parking.platform.common.exception.ResourceNotFoundException;
import com.parking.platform.deviceshadow.repository.DeviceShadowRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DeviceShadowSyncService 异常路径测试")
class DeviceShadowSyncServiceExceptionTest {

    private DeviceShadowRepository repository;
    private DeviceShadowSyncService service;

    @BeforeEach
    void setUp() {
        repository = new DeviceShadowRepository();
        service = new DeviceShadowSyncService(repository);
    }

    @AfterEach
    void tearDown() {
        service.clearAll();
        service.shutdown();
    }

    @Nested
    @DisplayName("资源不存在异常测试")
    class ResourceNotFoundTests {

        @Test
        @DisplayName("获取不存在的设备影子应该抛出ResourceNotFoundException")
        void testGetShadowByDeviceId_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.getShadowByDeviceId("non_existent_device"));
            assertEquals(404, ex.getCode());
            assertTrue(ex.getMessage().contains("Device shadow not found"));
        }

        @Test
        @DisplayName("sync不存在的设备应该抛出ResourceNotFoundException")
        void testSyncWithDevice_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.syncWithDevice("non_existent_device"));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("getDiff不存在的设备应该抛出ResourceNotFoundException")
        void testGetDiff_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.getDiff("non_existent_device"));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("删除不存在的设备影子应该抛出ResourceNotFoundException")
        void testDeleteShadow_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.deleteShadow("non_existent_device"));
            assertEquals(404, ex.getCode());
        }
    }

    @Nested
    @DisplayName("同步失败异常测试")
    class SyncFailureTests {

        @Test
        @DisplayName("同步失败 - 状态应该变为sync_failed")
        void testSyncWithDevice_SyncFailure_StatusUpdate() throws Exception {
            String deviceId = "fail-device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("key", "value");
            service.updateDesiredState(deviceId, desired);

            repository.setSimulateSyncFailure(true);

            DeviceShadowSyncException ex = assertThrows(DeviceShadowSyncException.class,
                    () -> service.syncWithDevice(deviceId));
            assertTrue(ex.getMessage().contains("Simulated device connection failure"));

            DeviceShadowEntity shadow = service.getShadowByDeviceId(deviceId);
            assertEquals("sync_failed", shadow.getStatus());
        }

        @Test
        @DisplayName("同步失败 - 多次重试后最终失败")
        void testSyncWithDevice_MultipleRetries_ThenFailure() {
            String deviceId = "retry-device-001";
            service.setMaxRetryAttempts(3);
            service.setRetryDelayMs(10);

            Map<String, Object> desired = new HashMap<>();
            desired.put("key", "value");
            service.updateDesiredState(deviceId, desired);

            repository.setSimulateSyncFailure(true);

            long startTime = System.currentTimeMillis();
            DeviceShadowSyncException ex = assertThrows(DeviceShadowSyncException.class,
                    () -> service.syncWithDevice(deviceId));
            long duration = System.currentTimeMillis() - startTime;

            assertTrue(ex.getMessage().contains("3 attempts"));
            assertTrue(duration >= 30);
        }

        @Test
        @DisplayName("同步失败后恢复 - 应该成功同步")
        void testSyncWithDevice_FailureThenRecovery() throws Exception {
            String deviceId = "recovery-device-001";
            service.setMaxRetryAttempts(3);
            service.setRetryDelayMs(10);

            Map<String, Object> desired = new HashMap<>();
            desired.put("key", "value");
            service.updateDesiredState(deviceId, desired);

            repository.setSimulateSyncFailure(true);
            assertThrows(DeviceShadowSyncException.class,
                    () -> service.syncWithDevice(deviceId));

            repository.setSimulateSyncFailure(false);

            DeviceShadowEntity synced = service.syncWithDevice(deviceId);
            assertTrue(synced.isSynced());
            assertEquals("synced", synced.getStatus());
        }

        @Test
        @DisplayName("异步sync失败 - Future应该包含异常")
        void testAsyncSync_Failure_FutureHasException() throws Exception {
            String deviceId = "async-fail-device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("key", "value");
            service.updateDesiredState(deviceId, desired);

            repository.setSimulateSyncFailure(true);

            Future<DeviceShadowEntity> future = service.syncWithDeviceAsync(deviceId);

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof DeviceShadowSyncException);
        }

        @Test
        @DisplayName("已同步的设备再次sync - 应该直接返回，不触发同步操作")
        void testSyncWithDevice_AlreadySynced() throws Exception {
            String deviceId = "synced-device-001";

            Map<String, Object> state = new HashMap<>();
            state.put("key", "value");

            service.updateDesiredState(deviceId, state);
            service.updateReportedState(deviceId, state);

            DeviceShadowEntity shadow = service.getShadowByDeviceId(deviceId);
            assertTrue(shadow.isSynced());

            DeviceShadowEntity result = service.syncWithDevice(deviceId);
            assertTrue(result.isSynced());
        }
    }

    @Nested
    @DisplayName("存储层故障测试")
    class StorageFailureTests {

        @Test
        @DisplayName("存储层故障 - 创建设备影子应该失败")
        void testCreateShadow_StorageFailure() {
            repository.setSimulateStorageFailure(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.createShadow("device-001"));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("存储层故障 - 更新desired应该失败")
        void testUpdateDesiredState_StorageFailure() {
            String deviceId = "device-001";
            service.createShadow(deviceId);

            repository.setSimulateStorageFailure(true);

            Map<String, Object> state = new HashMap<>();
            state.put("key", "value");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.updateDesiredState(deviceId, state));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("存储层故障 - 获取影子应该失败")
        void testGetShadow_StorageFailure() {
            String deviceId = "device-001";
            service.createShadow(deviceId);

            repository.setSimulateStorageFailure(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.getShadowByDeviceId(deviceId));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("存储层故障 - sync操作中存储层失败")
        void testSyncWithDevice_StorageFailureDuringSync() {
            String deviceId = "device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("key", "value");
            service.updateDesiredState(deviceId, desired);

            repository.setSimulateStorageFailure(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.syncWithDevice(deviceId));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("存储层故障恢复后应该正常工作")
        void testStorageFailureRecovery() throws Exception {
            String deviceId = "recovery-device-001";
            service.createShadow(deviceId);

            repository.setSimulateStorageFailure(true);
            assertThrows(RuntimeException.class,
                    () -> service.getShadowByDeviceId(deviceId));

            repository.setSimulateStorageFailure(false);
            DeviceShadowEntity recovered = service.getShadowByDeviceId(deviceId);
            assertNotNull(recovered);
            assertEquals(deviceId, recovered.getDeviceId());
        }
    }

    @Nested
    @DisplayName("批量同步异常测试")
    class BatchSyncExceptionTests {

        @Test
        @DisplayName("批量同步 - 单个设备失败应该抛出异常")
        void testBatchSync_SingleDeviceFailure() {
            service.setMaxRetryAttempts(1);
            service.setRetryDelayMs(1);

            List<String> deviceIds = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String deviceId = "batch-" + i;
                Map<String, Object> desired = new HashMap<>();
                desired.put("key", i);
                service.updateDesiredState(deviceId, desired);
                deviceIds.add(deviceId);
            }

            repository.setSimulateSyncFailure(true);

            DeviceShadowSyncException ex = assertThrows(DeviceShadowSyncException.class,
                    () -> service.batchSync(deviceIds, 10000));
            assertTrue(ex.getMessage().contains("Batch sync failed") || ex.getMessage().contains("Simulated"));
        }

        @Test
        @DisplayName("批量同步 - 超时应该抛出TimeoutException")
        void testBatchSync_Timeout() {
            int deviceCount = 10;
            List<String> deviceIds = new ArrayList<>();

            for (int i = 0; i < deviceCount; i++) {
                String deviceId = "timeout-batch-" + i;
                Map<String, Object> desired = new HashMap<>();
                desired.put("key", i);
                service.updateDesiredState(deviceId, desired);
                deviceIds.add(deviceId);
            }

            TimeoutException ex = assertThrows(TimeoutException.class,
                    () -> service.batchSync(deviceIds, 1));
            assertTrue(ex.getMessage().contains("timed out"));
        }
    }

    @Nested
    @DisplayName("状态边界异常测试")
    class StatusEdgeTests {

        @Test
        @DisplayName("reported有null值时 - sync应该正常处理")
        void testSyncWithNullInReported() throws Exception {
            String deviceId = "null-test-device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("key1", "value1");
            desired.put("key2", null);
            service.updateDesiredState(deviceId, desired);

            DeviceShadowEntity synced = service.syncWithDevice(deviceId);
            assertTrue(synced.isSynced());
        }

        @Test
        @DisplayName("删除设备后 - 再次查询应该抛出异常")
        void testDeleteThenQuery() {
            String deviceId = "delete-test-001";
            service.createShadow(deviceId);

            service.deleteShadow(deviceId);

            assertThrows(ResourceNotFoundException.class,
                    () -> service.getShadowByDeviceId(deviceId));
        }

        @Test
        @DisplayName("多次删除 - 第二次应该抛出异常")
        void testDoubleDelete() {
            String deviceId = "double-delete-001";
            service.createShadow(deviceId);

            service.deleteShadow(deviceId);

            assertThrows(ResourceNotFoundException.class,
                    () -> service.deleteShadow(deviceId));
        }
    }

    @Nested
    @DisplayName("重试机制测试")
    class RetryMechanismTests {

        @Test
        @DisplayName("设置重试次数为1 - 失败后不重试")
        void testRetryAttemptsSetToOne() {
            String deviceId = "retry-1-device-001";
            service.setMaxRetryAttempts(1);
            service.setRetryDelayMs(0);

            Map<String, Object> desired = new HashMap<>();
            desired.put("key", "value");
            service.updateDesiredState(deviceId, desired);

            repository.setSimulateSyncFailure(true);

            long startTime = System.currentTimeMillis();
            DeviceShadowSyncException ex = assertThrows(DeviceShadowSyncException.class,
                    () -> service.syncWithDevice(deviceId));
            long duration = System.currentTimeMillis() - startTime;

            assertTrue(ex.getMessage().contains("1 attempts"));
            assertTrue(duration < 100);
        }

        @Test
        @DisplayName("重试过程中恢复 - 应该成功")
        void testRetryRecovery() {
            String deviceId = "retry-recovery-001";
            service.setMaxRetryAttempts(5);
            service.setRetryDelayMs(50);

            Map<String, Object> desired = new HashMap<>();
            desired.put("key", "value");
            service.updateDesiredState(deviceId, desired);

            repository.setSimulateSyncFailure(true);

            new Thread(() -> {
                try {
                    Thread.sleep(100);
                    repository.setSimulateSyncFailure(false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

            DeviceShadowEntity synced = null;
            try {
                synced = service.syncWithDevice(deviceId);
            } catch (Exception e) {
            }

            assertNotNull(synced);
            assertTrue(synced.isSynced());
        }
    }
}
