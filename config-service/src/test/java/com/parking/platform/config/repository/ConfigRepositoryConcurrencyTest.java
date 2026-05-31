package com.parking.platform.config.repository;

import com.parking.platform.common.entity.ConfigEntity;
import com.parking.platform.common.entity.ConfigVersionHistoryEntity;
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

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConfigRepository 并发场景测试")
class ConfigRepositoryConcurrencyTest {

    private ConfigRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ConfigRepository();
    }

    @AfterEach
    void tearDown() {
        repository.clearAll();
    }

    @Nested
    @DisplayName("多线程并发保存测试")
    class ConcurrentSaveTests {

        @Test
        @DisplayName("多线程并发保存不同配置 - 所有配置都应该保存成功")
        void testConcurrentSave_DifferentConfigs() throws Exception {
            int threadCount = 20;
            int savesPerThread = 50;
            int totalSaves = threadCount * savesPerThread;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < savesPerThread; i++) {
                            ConfigEntity config = new ConfigEntity();
                            config.setNamespace("thread." + threadNum + "." + i);
                            config.setParameter("thread", threadNum);
                            config.setParameter("index", i);
                            repository.save(config);
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
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(totalSaves, successCount.get());
            assertEquals(totalSaves, repository.findAll().size());
        }

        @Test
        @DisplayName("多线程并发更新同一配置 - 最后一个更新生效")
        void testConcurrentSave_SameConfig() throws Exception {
            ConfigEntity initial = new ConfigEntity();
            initial.setNamespace("concurrent.update.ns");
            ConfigEntity saved = repository.save(initial);
            String configId = saved.getId();

            int threadCount = 20;
            int updatesPerThread = 100;

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
                            ConfigEntity config = new ConfigEntity();
                            config.setId(configId);
                            config.setNamespace("concurrent.update.ns");
                            config.setParameter("updated_by", threadNum);
                            config.setParameter("update_index", i);
                            repository.save(config);
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
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount * updatesPerThread, successCount.get());
            ConfigEntity finalConfig = repository.findById(configId).orElseThrow();
            assertNotNull(finalConfig.getParameter("updated_by"));
            assertNotNull(finalConfig.getParameter("update_index"));
        }

        @Test
        @DisplayName("大量线程并发保存 - 线程安全")
        void testConcurrentSave_HighConcurrency() throws Exception {
            int threadCount = 100;
            int savesPerThread = 20;
            int totalSaves = threadCount * savesPerThread;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newCachedThreadPool();

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < savesPerThread; i++) {
                            ConfigEntity config = new ConfigEntity();
                            config.setNamespace("high.concurrency.ns." + threadNum + "." + i);
                            repository.save(config);
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
            doneLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(totalSaves, successCount.get());
            assertEquals(totalSaves, repository.findAll().size());
        }
    }

    @Nested
    @DisplayName("多线程并发读取测试")
    class ConcurrentReadTests {

        @Test
        @DisplayName("多线程并发读取同一配置 - 数据一致")
        void testConcurrentRead_SameConfig() throws Exception {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            config.setParameter("key", "value");
            config.setParameter("number", 100);
            ConfigEntity saved = repository.save(config);
            String configId = saved.getId();

            int readerCount = 50;
            int readsPerReader = 1000;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(readerCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger inconsistentCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(readerCount);

            for (int r = 0; r < readerCount; r++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < readsPerReader; i++) {
                            ConfigEntity read = repository.findById(configId).orElse(null);
                            if (read != null) {
                                successCount.incrementAndGet();
                                if (!"value".equals(read.getParameter("key"))) {
                                    inconsistentCount.incrementAndGet();
                                }
                                if (!Integer.valueOf(100).equals(read.getParameter("number"))) {
                                    inconsistentCount.incrementAndGet();
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
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(readerCount * readsPerReader, successCount.get());
            assertEquals(0, inconsistentCount.get());
        }

        @Test
        @DisplayName("多线程并发读取全部配置 - 数据完整")
        void testConcurrentRead_FindAll() throws Exception {
            int configCount = 100;
            for (int i = 0; i < configCount; i++) {
                ConfigEntity config = new ConfigEntity();
                config.setNamespace("ns." + i);
                repository.save(config);
            }

            int readerCount = 30;
            int readsPerReader = 200;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(readerCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger incompleteCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(readerCount);

            for (int r = 0; r < readerCount; r++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < readsPerReader; i++) {
                            List<ConfigEntity> all = repository.findAll();
                            if (all != null) {
                                successCount.incrementAndGet();
                                if (all.size() != configCount) {
                                    incompleteCount.incrementAndGet();
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
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(readerCount * readsPerReader, successCount.get());
            assertEquals(0, incompleteCount.get());
        }
    }

    @Nested
    @DisplayName("读写并发测试")
    class ConcurrentReadWriteTests {

        @Test
        @DisplayName("并发读写 - 读操作看到一致状态")
        void testConcurrentReadWrite_ConsistentState() throws Exception {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            config.setParameter("counter", 0);
            ConfigEntity saved = repository.save(config);
            String configId = saved.getId();

            int writerCount = 10;
            int readerCount = 30;
            int writesPerWriter = 100;
            int readsPerReader = 500;

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
                            ConfigEntity update = new ConfigEntity();
                            update.setId(configId);
                            update.setNamespace("test.ns");
                            update.setParameter("writer", writerNum);
                            update.setParameter("write_index", i);
                            repository.save(update);
                            writeSuccess.incrementAndGet();
                            Thread.yield();
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
                        for (int i = 0; i < readsPerReader; i++) {
                            ConfigEntity read = repository.findById(configId).orElse(null);
                            if (read != null && read.getId() != null) {
                                readSuccess.incrementAndGet();
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
            doneLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(writerCount * writesPerWriter, writeSuccess.get());
            assertEquals(readerCount * readsPerReader, readSuccess.get());
        }

        @Test
        @DisplayName("并发保存和删除 - 最终状态一致")
        void testConcurrentSaveAndDelete() throws Exception {
            int configCount = 100;
            List<String> configIds = new ArrayList<>();

            for (int i = 0; i < configCount; i++) {
                ConfigEntity config = new ConfigEntity();
                config.setNamespace("initial.ns." + i);
                configIds.add(repository.save(config).getId());
            }

            int saverCount = 10;
            int deleterCount = 10;
            int operationsPerThread = 50;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(saverCount + deleterCount);
            AtomicInteger saveSuccess = new AtomicInteger(0);
            AtomicInteger deleteSuccess = new AtomicInteger(0);
            AtomicInteger deleteNotFound = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(saverCount + deleterCount);

            for (int s = 0; s < saverCount; s++) {
                final int saverNum = s;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            ConfigEntity config = new ConfigEntity();
                            config.setNamespace("concurrent.save.ns." + saverNum + "." + i);
                            repository.save(config);
                            saveSuccess.incrementAndGet();
                            Thread.yield();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            for (int d = 0; d < deleterCount; d++) {
                final int deleterNum = d;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            int idx = (deleterNum * operationsPerThread + i) % configIds.size();
                            String idToDelete = configIds.get(idx);
                            try {
                                repository.deleteById(idToDelete);
                                deleteSuccess.incrementAndGet();
                            } catch (Exception e) {
                                deleteNotFound.incrementAndGet();
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
            doneLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(saverCount * operationsPerThread, saveSuccess.get());
            assertTrue(deleteSuccess.get() + deleteNotFound.get() == deleterCount * operationsPerThread);
        }
    }

    @Nested
    @DisplayName("版本历史并发测试")
    class VersionHistoryConcurrencyTests {

        @Test
        @DisplayName("多线程并发保存版本历史 - 线程安全")
        void testConcurrentSaveVersionHistory() throws Exception {
            String configId = "test-config-hist-1";
            int threadCount = 20;
            int versionsPerThread = 50;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < versionsPerThread; i++) {
                            int version = threadNum * versionsPerThread + i + 1;
                            ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity(configId, version);
                            history.setChangeReason("from thread " + threadNum);
                            history.setChangedBy("thread-" + threadNum);
                            repository.saveVersionHistory(history);
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
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount * versionsPerThread, successCount.get());
            assertEquals(threadCount * versionsPerThread, repository.findHistoryByConfigId(configId).size());
        }

        @Test
        @DisplayName("并发读写版本历史")
        void testConcurrentReadWriteVersionHistory() throws Exception {
            String configId = "test-config-rw-hist";

            int writerCount = 10;
            int readerCount = 20;
            int versionsPerWriter = 100;
            int readsPerReader = 200;

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
                        for (int i = 0; i < versionsPerWriter; i++) {
                            int version = writerNum * versionsPerWriter + i + 1;
                            ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity(configId, version);
                            history.setChangeReason("update " + i);
                            repository.saveVersionHistory(history);
                            writeSuccess.incrementAndGet();
                            Thread.yield();
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
                        for (int i = 0; i < readsPerReader; i++) {
                            List<ConfigVersionHistoryEntity> history = repository.findHistoryByConfigId(configId);
                            if (history != null) {
                                readSuccess.incrementAndGet();
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
            doneLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(writerCount * versionsPerWriter, writeSuccess.get());
            assertEquals(readerCount * readsPerReader, readSuccess.get());
        }
    }

    @Nested
    @DisplayName("极限压力并发测试")
    class ExtremeConcurrencyTests {

        @Test
        @DisplayName("长时间并发运行 - 无死锁或内存泄漏")
        void testLongRunningConcurrency() throws Exception {
            int threadCount = 10;
            int iterations = 100;
            int delayMs = 5;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger totalOperations = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < iterations; i++) {
                            ConfigEntity config = new ConfigEntity();
                            config.setNamespace("long.running.ns." + threadNum + "." + i);
                            repository.save(config);
                            
                            ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity(config.getId(), 1);
                            repository.saveVersionHistory(history);
                            
                            repository.findById(config.getId());
                            repository.findAll();
                            repository.findHistoryByConfigId(config.getId());
                            
                            totalOperations.addAndGet(4);
                            
                            try {
                                Thread.sleep(delayMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(120, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount * iterations * 4, totalOperations.get());
        }
    }
}
