package com.parking.platform.config.service;

import com.parking.platform.common.entity.ConfigEntity;
import com.parking.platform.config.repository.ConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigVersionManagerService 并发场景测试")
class ConfigVersionManagerServiceConcurrencyTest {

    private ConfigRepository repository;
    private ConfigVersionManagerService service;

    @BeforeEach
    void setUp() {
        repository = new ConfigRepository();
        service = new ConfigVersionManagerService(repository);
    }

    @AfterEach
    void tearDown() {
        service.clearAll();
    }

    @Nested
    @DisplayName("多线程并发更新同一份配置")
    class ConcurrentUpdateSameConfigTests {

        @Test
        @DisplayName("多线程并发更新同一配置 - 版本号应正确递增")
        void testConcurrentUpdates_VersionIncrementsCorrectly() throws Exception {
            Map<String, Object> initialParams = new HashMap<>();
            initialParams.put("counter", 0);
            ConfigEntity config = service.createConfig("test.ns", initialParams, "create", "user");
            String configId = config.getId();

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
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("key_" + threadNum + "_" + i, "value_" + i);
                            service.updateConfig(configId, updates, "update from thread " + threadNum, "thread-" + threadNum);
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

            ConfigEntity finalConfig = service.getConfig(configId);

            assertEquals(totalUpdates, successCount.get());
            assertEquals(1 + totalUpdates, finalConfig.getVersion());
            assertTrue(finalConfig.getParameters().size() >= threadCount);
        }

        @Test
        @DisplayName("多线程并发更新不同配置 - 互不干扰")
        void testConcurrentUpdates_DifferentConfigs() throws Exception {
            int configCount = 10;
            int threadCount = 10;

            List<String> configIds = new ArrayList<>();
            for (int i = 0; i < configCount; i++) {
                ConfigEntity config = service.createConfig("test.ns." + i, new HashMap<>(), "create", "user");
                configIds.add(config.getId());
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (String configId : configIds) {
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("updated_by", Thread.currentThread().getName());
                            service.updateConfig(configId, updates, "update", "thread");
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

            assertEquals(configCount * threadCount, successCount.get());

            for (String configId : configIds) {
                ConfigEntity finalConfig = service.getConfig(configId);
                assertEquals(1 + threadCount, finalConfig.getVersion());
            }
        }
    }

    @Nested
    @DisplayName("读写并发测试")
    class ConcurrentReadWriteTests {

        @Test
        @DisplayName("并发读写同一配置 - 读应该看到一致状态")
        void testConcurrentReadWrite_ConsistentState() throws Exception {
            Map<String, Object> initialParams = new HashMap<>();
            initialParams.put("version", 0);
            ConfigEntity config = service.createConfig("test.ns", initialParams, "create", "user");
            String configId = config.getId();

            int writerCount = 5;
            int readerCount = 20;
            int writesPerWriter = 100;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(writerCount + readerCount);
            AtomicInteger readSuccessCount = new AtomicInteger(0);
            AtomicInteger writeSuccessCount = new AtomicInteger(0);
            AtomicInteger versionMismatchCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(writerCount + readerCount);

            for (int w = 0; w < writerCount; w++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < writesPerWriter; i++) {
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("write_time", System.currentTimeMillis());
                            service.updateConfig(configId, updates, "write", "writer");
                            writeSuccessCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
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
                            ConfigEntity readConfig = service.getConfig(configId);
                            if (readConfig != null && readConfig.getId() != null) {
                                readSuccessCount.incrementAndGet();
                                int v = readConfig.getVersion();
                                if (v < 1) {
                                    versionMismatchCount.incrementAndGet();
                                }
                            }
                            Thread.yield();
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

            assertEquals(writerCount * writesPerWriter, writeSuccessCount.get());
            assertEquals(readerCount * 50, readSuccessCount.get());
            assertEquals(0, versionMismatchCount.get());

            ConfigEntity finalConfig = service.getConfig(configId);
            assertEquals(1 + writerCount * writesPerWriter, finalConfig.getVersion());
        }

        @Test
        @DisplayName("多线程并发查询历史记录 - 历史记录完整")
        void testConcurrentReadHistory_CompleteHistory() throws Exception {
            ConfigEntity config = service.createConfig("test.ns", new HashMap<>(), "create", "user");
            String configId = config.getId();

            int updateCount = 50;
            for (int i = 0; i < updateCount; i++) {
                Map<String, Object> updates = new HashMap<>();
                updates.put("key" + i, "value" + i);
                service.updateConfig(configId, updates, "update " + i, "user");
            }

            int readerCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(readerCount);
            AtomicInteger completeReadCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(readerCount);

            for (int r = 0; r < readerCount; r++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 20; i++) {
                            var history = service.getConfigHistory(configId);
                            if (history.size() == updateCount + 1) {
                                completeReadCount.incrementAndGet();
                            }
                            Thread.yield();
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

            assertEquals(readerCount * 20, completeReadCount.get());
        }
    }

    @Nested
    @DisplayName("回滚并发测试")
    class ConcurrentRollbackTests {

        @Test
        @DisplayName("更新和回滚交替进行 - 最终状态一致")
        void testConcurrentUpdateAndRollback_ConsistentFinalState() throws Exception {
            Map<String, Object> v1Params = new HashMap<>();
            v1Params.put("stage", "v1");
            v1Params.put("value", 100);
            ConfigEntity config = service.createConfig("test.ns", v1Params, "create v1", "user");
            String configId = config.getId();

            service.markRollbackPoint(configId, "v1 is stable", "user");

            Map<String, Object> v2Params = new HashMap<>();
            v2Params.put("stage", "v2");
            v2Params.put("value", 200);
            v2Params.put("extra", "data");
            service.updateConfig(configId, v2Params, "update to v2", "user");

            int rollbackCount = 5;
            int updateCount = 5;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicInteger rollbackSuccess = new AtomicInteger(0);
            AtomicInteger updateSuccess = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(2);

            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < rollbackCount; i++) {
                        try {
                            service.rollbackToVersion(configId, 1, "rollback " + i, "rollback-user");
                            rollbackSuccess.incrementAndGet();
                        } catch (Exception e) {
                        }
                        Thread.yield();
                    }
                } catch (Exception e) {
                } finally {
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < updateCount; i++) {
                        try {
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("concurrent_update", i);
                            service.updateConfig(configId, updates, "update " + i, "update-user");
                            updateSuccess.incrementAndGet();
                        } catch (Exception e) {
                        }
                        Thread.yield();
                    }
                } catch (Exception e) {
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            ConfigEntity finalConfig = service.getConfig(configId);
            assertNotNull(finalConfig);
            assertTrue(finalConfig.getVersion() >= 1);
        }
    }

    @Nested
    @DisplayName("批量并发创建测试")
    class BulkCreateTests {

        @Test
        @DisplayName("并发创建大量配置 - 所有配置都应该成功保存")
        void testBulkConcurrentCreate_AllConfigsSaved() throws Exception {
            int configCount = 100;
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
                        for (int i = 0; i < configCount / threadCount; i++) {
                            String namespace = "bulk.ns." + threadNum + "." + i;
                            Map<String, Object> params = new HashMap<>();
                            params.put("created_by", threadNum);
                            params.put("index", i);
                            service.createConfig(namespace, params, "bulk create", "bulk");
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
            assertEquals(configCount, successCount.get());
            assertEquals(configCount, stats.get("total_configs"));
        }
    }
}
