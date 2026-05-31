package com.parking.platform.deviceshadow.service;

import com.parking.platform.common.entity.DeviceShadowEntity;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DeviceShadowSyncService 并发场景测试")
class DeviceShadowSyncServiceConcurrencyTest {

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
    @DisplayName("多线程并发更新同一设备状态")
    class ConcurrentUpdateSameDeviceTests {

        @Test
        @DisplayName("多线程并发更新同一设备的desired状态 - 所有更新应该成功保存")
        void testConcurrentUpdateDesired_AllUpdatesSaved() throws Exception {
            String deviceId = "concurrent-device-001";
            service.createShadow(deviceId);

            int threadCount = 10;
            int updatesPerThread = 100;
            int totalUpdates = threadCount * updatesPerThread;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < updatesPerThread; i++) {
                            Map<String, Object> state = new HashMap<>();
                            state.put("t" + threadNum + "_k" + i, "value_" + i);
                            service.updateDesiredState(deviceId, state);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            DeviceShadowEntity finalShadow = service.getShadowByDeviceId(deviceId);

            assertEquals(totalUpdates, successCount.get());
            assertEquals(1 + totalUpdates, finalShadow.getDesiredVersion());
        }

        @Test
        @DisplayName("多线程并发更新不同设备的状态 - 互不干扰")
        void testConcurrentUpdateDifferentDevices_NoInterference() throws Exception {
            int deviceCount = 10;
            int threadCount = 10;

            List<String> deviceIds = new ArrayList<>();
            for (int i = 0; i < deviceCount; i++) {
                String deviceId = "device-" + i;
                service.createShadow(deviceId);
                deviceIds.add(deviceId);
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (String deviceId : deviceIds) {
                            Map<String, Object> state = new HashMap<>();
                            state.put("updated_by_thread", threadNum);
                            service.updateDesiredState(deviceId, state);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            assertEquals(deviceCount * threadCount, successCount.get());

            for (String deviceId : deviceIds) {
                DeviceShadowEntity shadow = service.getShadowByDeviceId(deviceId);
                assertEquals(1 + threadCount, shadow.getDesiredVersion());
            }
        }

        @Test
        @DisplayName("desired和reported并发更新 - 各自版本号独立递增")
        void testConcurrentDesiredReportedUpdates_IndependentVersions() throws Exception {
            String deviceId = "mixed-device-001";
            service.createShadow(deviceId);

            int threadCount = 10;
            int updatesPerThread = 50;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2 * threadCount);
            AtomicInteger desiredSuccess = new AtomicInteger(0);
            AtomicInteger reportedSuccess = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(2 * threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < updatesPerThread; i++) {
                            Map<String, Object> state = new HashMap<>();
                            state.put("desired_key", i);
                            service.updateDesiredState(deviceId, state);
                            desiredSuccess.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < updatesPerThread; i++) {
                            Map<String, Object> state = new HashMap<>();
                            state.put("reported_key", i);
                            service.updateReportedState(deviceId, state);
                            reportedSuccess.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            assertEquals(threadCount * updatesPerThread, desiredSuccess.get());
            assertEquals(threadCount * updatesPerThread, reportedSuccess.get());

            DeviceShadowEntity finalShadow = service.getShadowByDeviceId(deviceId);
            assertEquals(1 + threadCount * updatesPerThread, finalShadow.getDesiredVersion());
            assertEquals(1 + threadCount * updatesPerThread, finalShadow.getReportedVersion());
        }
    }

    @Nested
    @DisplayName("同步并发测试")
    class ConcurrentSyncTests {

        @Test
        @DisplayName("同一设备多次并发sync - 应该串行执行")
        void testConcurrentSync_SameDevice_Serialized() throws Exception {
            String deviceId = "sync-device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("config", "value");
            service.updateDesiredState(deviceId, desired);

            int syncCount = 5;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(syncCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger concurrentCount = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(syncCount);

            for (int s = 0; s < syncCount; s++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        int current = concurrentCount.incrementAndGet();
                        maxConcurrent.updateAndGet(m -> Math.max(m, current));
                        try {
                            service.syncWithDevice(deviceId);
                            successCount.incrementAndGet();
                        } finally {
                            concurrentCount.decrementAndGet();
                        }
                    } catch (Exception e) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            assertEquals(syncCount, successCount.get());
            assertEquals(1, maxConcurrent.get());
        }

        @Test
        @DisplayName("多设备批量同步 - 所有设备都应该同步成功")
        void testBatchSync_AllDevicesSynced() throws Exception {
            int deviceCount = 20;
            List<String> deviceIds = new ArrayList<>();

            for (int i = 0; i < deviceCount; i++) {
                String deviceId = "batch-device-" + i;
                Map<String, Object> desired = new HashMap<>();
                desired.put("target", i);
                service.updateDesiredState(deviceId, desired);
                deviceIds.add(deviceId);
            }

            List<DeviceShadowEntity> results = service.batchSync(deviceIds, 30000);

            assertEquals(deviceCount, results.size());
            for (DeviceShadowEntity shadow : results) {
                assertTrue(shadow.isSynced());
                assertEquals("synced", shadow.getStatus());
                assertNotNull(shadow.getLastSyncAt());
            }
        }

        @Test
        @DisplayName("异步sync - Future应该正确返回结果")
        void testAsyncSync_FutureReturnsCorrectly() throws Exception {
            String deviceId = "async-device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("async_key", "async_value");
            service.updateDesiredState(deviceId, desired);

            Future<DeviceShadowEntity> future = service.syncWithDeviceAsync(deviceId);

            DeviceShadowEntity result = future.get(10, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.isSynced());
            assertEquals("synced", result.getStatus());
        }
    }

    @Nested
    @DisplayName("读写并发测试")
    class ConcurrentReadWriteTests {

        @Test
        @DisplayName("并发读写同一设备 - 读操作应该看到一致状态")
        void testConcurrentReadWrite_ConsistentReads() throws Exception {
            String deviceId = "rw-device-001";
            service.createShadow(deviceId);

            int writerCount = 5;
            int readerCount = 20;
            int writesPerWriter = 100;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(writerCount + readerCount);
            AtomicInteger writeSuccess = new AtomicInteger(0);
            AtomicInteger readSuccess = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(writerCount + readerCount);

            for (int w = 0; w < writerCount; w++) {
                final int writerNum = w;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < writesPerWriter; i++) {
                            Map<String, Object> state = new HashMap<>();
                            state.put("writer" + writerNum, i);
                            service.updateDesiredState(deviceId, state);
                            writeSuccess.incrementAndGet();
                        }
                    } catch (Exception e) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            for (int r = 0; r < readerCount; r++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 50; i++) {
                            DeviceShadowEntity shadow = service.getShadowByDeviceId(deviceId);
                            if (shadow != null && shadow.getDeviceId() != null) {
                                readSuccess.incrementAndGet();
                            }
                            Thread.yield();
                        }
                    } catch (Exception e) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            assertEquals(writerCount * writesPerWriter, writeSuccess.get());
            assertEquals(readerCount * 50, readSuccess.get());

            DeviceShadowEntity finalShadow = service.getShadowByDeviceId(deviceId);
            assertEquals(1 + writerCount * writesPerWriter, finalShadow.getDesiredVersion());
        }

        @Test
        @DisplayName("大量设备并发创建 - 所有设备应该成功创建")
        void testBulkConcurrentCreate_AllDevicesCreated() throws Exception {
            int deviceCount = 100;
            int threadCount = 10;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < deviceCount / threadCount; i++) {
                            String deviceId = "bulk-" + threadNum + "-" + i;
                            service.createShadow(deviceId);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            Map<String, Object> stats = service.getStatistics();
            assertEquals(deviceCount, successCount.get());
            assertEquals(deviceCount, stats.get("total_shadows"));
        }
    }

    @Nested
    @DisplayName("getOrCreateShadow并发测试")
    class GetOrCreateConcurrencyTests {

        @Test
        @DisplayName("多线程并发调用getOrCreateShadow - 应该只有一个创建成功")
        void testConcurrentGetOrCreateShadow_SingleCreation() throws Exception {
            String deviceId = "goc-device-001";
            int threadCount = 20;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            List<String> retrievedIds = new ArrayList<>();

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        DeviceShadowEntity shadow = service.getOrCreateShadow(deviceId);
                        synchronized (retrievedIds) {
                            retrievedIds.add(shadow.getId());
                        }
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            assertEquals(threadCount, successCount.get());

            long uniqueIds = retrievedIds.stream().distinct().count();
            assertEquals(1, uniqueIds);

            Map<String, Object> stats = service.getStatistics();
            assertEquals(1, stats.get("total_shadows"));
        }
    }
}
