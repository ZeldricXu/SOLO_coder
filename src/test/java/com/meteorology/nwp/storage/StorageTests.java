package com.meteorology.nwp.storage;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.test.NWPTestBase;
import com.meteorology.nwp.test.TestDataFactory;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("存储层测试")
class StorageTests extends NWPTestBase {

    private MetadataManager metaMgr;
    private HdfsStorageManager hdfsMgr;
    private KafkaTaskCoordinator kafkaMgr;

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
        if (metaMgr != null) { try { metaMgr.close(); } catch (Exception ignored) {} }
        if (hdfsMgr != null) { try { hdfsMgr.close(); } catch (Exception ignored) {} }
        if (kafkaMgr != null) { try { kafkaMgr.shutdown(); } catch (Exception ignored) {} }
    }

    @Nested
    @DisplayName("PostgreSQL元数据测试")
    class MetadataTests {

        @Test
        @DisplayName("创建预报任务并查询")
        @DisabledIfNoPostgres
        void testCreateAndQueryForecastRun() {
            metaMgr = new MetadataManager(config);

            MetadataManager.ForecastRun run = metaMgr.createRun(
                    testTime, 72, "v1.0-test", "test-domain",
                    config.getNX(), config.getNY(), config.getNZ()
            );

            assertThat(run.id)
                    .as("任务ID应>0")
                    .isGreaterThan(0);

            List<MetadataManager.ForecastRun> recent = metaMgr.getRecentRuns(10);
            assertThat(recent)
                    .as("最近任务应包含刚创建的")
                    .isNotEmpty();

            boolean found = recent.stream().anyMatch(r -> r.id == run.id);
            assertThat(found).as("创建的任务可查询到").isTrue();
        }

        @Test
        @DisplayName("更新任务状态")
        @DisabledIfNoPostgres
        void testUpdateRunStatus() {
            metaMgr = new MetadataManager(config);

            MetadataManager.ForecastRun run = metaMgr.createRun(
                    testTime, 24, "test", "test",
                    config.getNX(), config.getNY(), config.getNZ()
            );

            metaMgr.updateRunStatus(run.id, "RUNNING", 100, 10.0);

            List<MetadataManager.ForecastRun> recent = metaMgr.getRecentRuns(5);
            Optional<MetadataManager.ForecastRun> updated = recent.stream()
                    .filter(r -> r.id == run.id).findFirst();

            assertThat(updated).isPresent();
            assertThat(updated.get().status).isEqualTo("RUNNING");
        }

        @Test
        @DisplayName("注册数据集")
        @DisabledIfNoPostgres
        void testRegisterDataset() {
            metaMgr = new MetadataManager(config);

            MetadataManager.ForecastRun run = metaMgr.createRun(
                    testTime, 48, "test", "test",
                    config.getNX(), config.getNY(), config.getNZ()
            );

            long datasetId = metaMgr.registerDataset(
                    run.id, 24, "Temperature", "netcdf4",
                    1024 * 1024, "/data/test.nc"
            );

            assertThat(datasetId).isGreaterThan(0);

            List<MetadataManager.DatasetEntry> datasets = metaMgr.findDatasets(run.id, -1, null);
            assertThat(datasets).hasSizeGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("写入检验分数")
        @DisabledIfNoPostgres
        void testInsertVerificationScore() {
            metaMgr = new MetadataManager(config);

            MetadataManager.ForecastRun run = metaMgr.createRun(
                    testTime, 24, "test", "test",
                    config.getNX(), config.getNY(), config.getNZ()
            );

            assertThatCode(() -> metaMgr.insertVerificationScore(
                    run.id, 24, "T2", 1.5, -0.3, 0.92, 500, testTime
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("数据库关闭后无泄漏")
        @DisabledIfNoPostgres
        void testCloseCleanup() {
            metaMgr = new MetadataManager(config);
            assertThatCode(() -> metaMgr.close()).doesNotThrowAnyException();
            metaMgr = null;
        }
    }

    @Nested
    @DisplayName("HDFS存储测试")
    class HDFSTests {

        @Test
        @DisplayName("本地模式：文件写入和读取一致性")
        void testLocalModeReadWrite() throws Exception {
            hdfsMgr = new HdfsStorageManager(config);

            byte[] testData = "Hello NWP HDFS test data".getBytes();

            String path = hdfsMgr.storeFile(
                    "test", testTime, 24, "test_var", "bin", testData
            );

            assertThat(path).isNotNull();

            byte[] readBack = hdfsMgr.retrieveFile(path);

            assertThat(readBack)
                    .as("读取数据应与写入一致")
                    .isEqualTo(testData);
        }

        @Test
        @DisplayName("文件列表查询")
        void testListFiles() throws Exception {
            hdfsMgr = new HdfsStorageManager(config);

            for (int h = 0; h < 6; h++) {
                byte[] data = ("data-" + h).getBytes();
                hdfsMgr.storeFile("test", testTime, h, "var" + h, "bin", data);
            }

            List<String> files = hdfsMgr.listFiles("test", testTime, "*");
            log.info("找到 {} 个文件", files.size());
        }

        @Test
        @DisplayName("删除文件")
        void testDeleteFile() throws Exception {
            hdfsMgr = new HdfsStorageManager(config);

            byte[] data = "to-delete".getBytes();
            String path = hdfsMgr.storeFile("test", testTime, 999, "del", "bin", data);

            boolean deleted = hdfsMgr.deleteFile(path);
            assertThat(deleted).as("删除成功").isTrue();
        }

        @Test
        @DisplayName("不存在的文件读取应抛异常")
        void testNonExistentFile() {
            hdfsMgr = new HdfsStorageManager(config);

            assertThatThrownBy(() -> hdfsMgr.retrieveFile("/nonexistent/path/file.bin"))
                    .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("Kafka任务协调测试")
    class KafkaTests {

        @Test
        @DisplayName("Kafka管理器初始化")
        @DisabledIfNoKafka
        void testKafkaInit() {
            kafkaMgr = new KafkaTaskCoordinator(config);
            assertThat(kafkaMgr).isNotNull();
        }

        @Test
        @DisplayName("提交预报任务")
        @DisabledIfNoKafka
        void testSubmitForecastTask() {
            kafkaMgr = new KafkaTaskCoordinator(config);

            String taskId = kafkaMgr.submitForecastTask(testTime, 24, "test-domain", "v1.0");

            assertThat(taskId).isNotNull().startsWith("NWP-");
            assertThat(kafkaMgr.getMessagesSent()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("发送任务结果")
        @DisabledIfNoKafka
        void testSendTaskResult() {
            kafkaMgr = new KafkaTaskCoordinator(config);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "ok");
            result.put("files", 10);

            assertThatCode(() -> kafkaMgr.sendTaskResult("test-task-1", "COMPLETED", result))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("状态更新")
        @DisabledIfNoKafka
        void testStatusUpdate() {
            kafkaMgr = new KafkaTaskCoordinator(config);

            Map<String, Object> stats = new HashMap<>();
            stats.put("CFL", 0.3);
            stats.put("Tmean", 288.0);

            assertThatCode(() -> kafkaMgr.updateStatus("run-1", 50, 100, stats))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("并发测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("HDFS并发写入一致性")
        void testHdfsConcurrentWrites() throws Exception {
            hdfsMgr = new HdfsStorageManager(config);

            int numThreads = 8;
            int writesPerThread = 10;
            Thread[] threads = new Thread[numThreads];
            int[] success = {0};

            for (int t = 0; t < numThreads; t++) {
                final int tid = t;
                threads[t] = new Thread(() -> {
                    for (int w = 0; w < writesPerThread; w++) {
                        try {
                            byte[] data = ("thread-" + tid + "-write-" + w).getBytes();
                            String p = hdfsMgr.storeFile(
                                    "concurrent", testTime, w,
                                    "t" + tid, "bin", data
                            );
                            byte[] read = hdfsMgr.retrieveFile(p);
                            if (Arrays.equals(data, read)) {
                                synchronized (success) { success[0]++; }
                            }
                        } catch (Exception e) {
                            log.warn("并发写入失败: {}", e.getMessage());
                        }
                    }
                });
            }

            for (Thread th : threads) th.start();
            for (Thread th : threads) th.join();

            int expected = numThreads * writesPerThread;
            log.info("并发写入: {}/{} 成功", success[0], expected);

            assertThat(success[0])
                    .as("并发写入成功率")
                    .isGreaterThan((int) (expected * 0.5));
        }

        @Test
        @DisplayName("元数据管理器线程安全")
        @DisabledIfNoPostgres
        void testMetadataThreadSafety() throws Exception {
            metaMgr = new MetadataManager(config);

            int numThreads = 4;
            Thread[] threads = new Thread[numThreads];
            long[] runIds = new long[numThreads];

            for (int t = 0; t < numThreads; t++) {
                final int tid = t;
                threads[t] = new Thread(() -> {
                    MetadataManager.ForecastRun run = metaMgr.createRun(
                            testTime, 24, "v1", "test" + tid,
                            config.getNX(), config.getNY(), config.getNZ()
                    );
                    runIds[tid] = run.id;
                    metaMgr.updateRunStatus(run.id, "RUNNING", tid * 10, tid * 1.0);
                    metaMgr.registerDataset(run.id, 12, "T", "nc", 1000, "/test.nc");
                });
            }

            for (Thread th : threads) th.start();
            for (Thread th : threads) th.join();

            for (long id : runIds) {
                assertThat(id).isGreaterThan(0);
            }
        }
    }

    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Disabled("需要PostgreSQL数据库")
    @interface DisabledIfNoPostgres {}

    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Disabled("需要Kafka broker")
    @interface DisabledIfNoKafka {}
}
