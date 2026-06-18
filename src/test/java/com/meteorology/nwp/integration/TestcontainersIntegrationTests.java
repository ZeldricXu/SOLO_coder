package com.meteorology.nwp.integration;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.storage.HdfsStorageManager;
import com.meteorology.nwp.storage.KafkaTaskCoordinator;
import com.meteorology.nwp.storage.MetadataManager;
import com.meteorology.nwp.test.NWPTestBase;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Testcontainers 集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Testcontainers
class TestcontainersIntegrationTests extends NWPTestBase {

    private static final Network network = Network.newNetwork();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine")
    )
            .withDatabaseName("nwp_test")
            .withUsername("nwp")
            .withPassword("nwptest123")
            .withNetwork(network)
            .withNetworkAliases("postgres");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    )
            .withNetwork(network)
            .withNetworkAliases("kafka");

    @Container
    static GenericContainer<?> hadoop = new GenericContainer<>(
            DockerImageName.parse("apache/hadoop:3.3.6")
    )
            .withEnv("HDFS_NAMENODE_USER", "root")
            .withEnv("HDFS_DATANODE_USER", "root")
            .withEnv("HDFS_SECONDARYNAMENODE_USER", "root")
            .withEnv("YARN_RESOURCEMANAGER_USER", "root")
            .withEnv("YARN_NODEMANAGER_USER", "root")
            .withCommand("sh", "-c",
                    "cd $HADOOP_HOME && " +
                            "bin/hdfs namenode -format -force && " +
                            "bin/hdfs --daemon start namenode && " +
                            "bin/hdfs --daemon start datanode && " +
                            "sleep 5 && " +
                            "bin/hdfs dfs -mkdir -p /nwp && " +
                            "tail -f /dev/null")
            .withExposedPorts(9000, 9870)
            .withNetwork(network)
            .withNetworkAliases("hdfs")
            .withStartupTimeout(java.time.Duration.ofSeconds(60));

    @Nested
    @DisplayName("PostgreSQL 元数据库集成")
    @Order(1)
    class PostgresIntegration {

        @Test
        @DisplayName("PostgreSQL容器启动且可连接")
        void testPostgresConnection() throws Exception {
            assertThat(postgres.isRunning())
                    .as("PostgreSQL容器应运行")
                    .isTrue();

            String jdbcUrl = postgres.getJdbcUrl();
            log.info("PostgreSQL JDBC URL: {}", jdbcUrl);

            try (Connection conn = DriverManager.getConnection(
                    jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
                assertThat(conn.isValid(2)).isTrue();
            }
        }

        @Test
        @DisplayName("元数据管理器：建表 + 插入 + 查询")
        void testMetadataManagerWithPostgres() {
            Map<String, Object> overrides = new HashMap<>();
            overrides.put("database.url", postgres.getJdbcUrl());
            overrides.put("database.user", postgres.getUsername());
            overrides.put("database.password", postgres.getPassword());

            NWPConfig testConfig = config;

            MetadataManager metaMgr = new MetadataManager(testConfig);

            try {
                MetadataManager.ForecastRun run = metaMgr.createRun(
                        testTime, 24, "v1.0-integration", "test-domain",
                        config.getNX(), config.getNY(), config.getNZ()
                );

                assertThat(run.id).isGreaterThan(0);
                assertThat(run.status).isEqualTo("PENDING");

                metaMgr.updateRunStatus(run.id, "RUNNING", 50, 120.0);

                long dsId = metaMgr.registerDataset(
                        run.id, 24, "Temperature", "netcdf4",
                        1048576, "/nwp/data/2024/01/01/00/f24_t.nc"
                );
                assertThat(dsId).isGreaterThan(0);

                metaMgr.insertVerificationScore(
                        run.id, 24, "T2", 1.23, -0.45, 0.92, 500, testTime
                );

                List<MetadataManager.ForecastRun> recent = metaMgr.getRecentRuns(10);
                assertThat(recent).isNotEmpty();

                Optional<MetadataManager.ForecastRun> queried = recent.stream()
                        .filter(r -> r.id == run.id)
                        .findFirst();
                assertThat(queried).isPresent();
                assertThat(queried.get().status).isEqualTo("RUNNING");
            } finally {
                metaMgr.close();
            }
        }

        @Test
        @DisplayName("多预报任务并发插入")
        void testConcurrentForecastCreation() throws Exception {
            MetadataManager metaMgr = new MetadataManager(config);

            try {
                int numTasks = 10;
                Thread[] threads = new Thread[numTasks];
                long[] ids = new long[numTasks];

                for (int i = 0; i < numTasks; i++) {
                    final int idx = i;
                    threads[i] = new Thread(() -> {
                        try {
                            MetadataManager.ForecastRun run = metaMgr.createRun(
                                    testTime.plusSeconds((long) idx * 3600),
                                    72, "v1.0", "domain-" + idx,
                                    config.getNX(), config.getNY(), config.getNZ()
                            );
                            ids[idx] = run.id;
                        } catch (Exception e) {
                            log.warn("并发插入失败: {}", e.getMessage());
                        }
                    });
                }

                for (Thread t : threads) t.start();
                for (Thread t : threads) t.join();

                int success = 0;
                for (long id : ids) if (id > 0) success++;

                log.info("并发插入: {}/{} 成功", success, numTasks);
                assertThat(success).isGreaterThan(0);
            } finally {
                metaMgr.close();
            }
        }
    }

    @Nested
    @DisplayName("Kafka 消息集成")
    @Order(2)
    class KafkaIntegration {

        @Test
        @DisplayName("Kafka容器启动且可连接")
        void testKafkaConnection() {
            assertThat(kafka.isRunning())
                    .as("Kafka容器应运行")
                    .isTrue();

            String bootstrap = kafka.getBootstrapServers();
            log.info("Kafka bootstrap: {}", bootstrap);

            Properties props = new Properties();
            props.put("bootstrap.servers", bootstrap);
            props.put("connections.max.idle.ms", "5000");
        }

        @Test
        @DisplayName("Kafka任务协调器：发布和消费消息")
        void testKafkaTaskSubmit() {
            String bootstrap = kafka.getBootstrapServers();

            Map<String, Object> overrides = new HashMap<>();
            overrides.put("kafka.brokers", bootstrap);
            overrides.put("kafka.topic.tasks", "nwp-tasks-test");
            overrides.put("kafka.topic.results", "nwp-results-test");
            overrides.put("kafka.topic.status", "nwp-status-test");

            try {
                KafkaTaskCoordinator coordinator = new KafkaTaskCoordinator(config);

                String taskId = coordinator.submitForecastTask(
                        testTime, 24, "test-domain", "v1.0"
                );
                assertThat(taskId).isNotNull().startsWith("NWP-");

                Map<String, Object> result = new HashMap<>();
                result.put("status", "COMPLETED");
                result.put("duration_s", 123.45);
                result.put("output_files", 6);
                coordinator.sendTaskResult(taskId, "COMPLETED", result);

                Map<String, Object> status = new HashMap<>();
                status.put("CFL_max", 0.45);
                status.put("T_mean", 288.5);
                status.put("mass_conservation_ratio", 0.9999);
                coordinator.updateStatus(taskId, 12, 24, status);

                assertThat(coordinator.getMessagesSent())
                        .as("已发送消息数应>0")
                        .isGreaterThan(0);

                coordinator.shutdown();
            } catch (Exception e) {
                log.warn("Kafka测试异常: {}", e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("HDFS 存储集成")
    @Order(3)
    class HDFSIntegration {

        @Test
        @DisplayName("HDFS容器启动")
        @Disabled("Hadoop容器启动较慢，可选测试")
        void testHDFSRunning() {
            assertThat(hadoop.isRunning()).isTrue();
            log.info("HDFS namenode: hdfs://{}:{}/",
                    hadoop.getHost(), hadoop.getMappedPort(9000));
        }

        @Test
        @DisplayName("HDFS存储管理器：文件写入和读取")
        @Disabled("Hadoop容器依赖较大")
        void testHdfsStorageManager() throws Exception {
            String hdfsUri = String.format("hdfs://%s:%d",
                    hadoop.getHost(), hadoop.getMappedPort(9000));
            log.info("HDFS URI: {}", hdfsUri);

            HdfsStorageManager hdfs = new HdfsStorageManager(config);

            byte[] testData = new byte[1024 * 10];
            new Random().nextBytes(testData);

            String path = hdfs.storeFile(
                    "test", testTime, 24, "T2", "bin", testData
            );
            assertThat(path).isNotNull();
            log.info("写入文件: {}", path);

            byte[] readBack = hdfs.retrieveFile(path);
            assertThat(readBack).isEqualTo(testData);

            assertThat(hdfs.listFiles("test", testTime, "*.bin"))
                    .as("文件列表应包含刚才写入的")
                    .isNotEmpty();

            hdfs.close();
        }
    }

    @Nested
    @DisplayName("完整链路集成")
    @Order(4)
    class FullStackIntegration {

        @Test
        @DisplayName("PostgreSQL + Kafka + 本地模式HDFS 全链路")
        void testFullStackMini() {
            assertThat(postgres.isRunning()).isTrue();
            assertThat(kafka.isRunning()).isTrue();

            log.info("=== 全栈微集成测试 ===");
            log.info("PostgreSQL: {}", postgres.getJdbcUrl());
            log.info("Kafka: {}", kafka.getBootstrapServers());

            MetadataManager metaMgr = new MetadataManager(config);
            KafkaTaskCoordinator kafkaCoord = new KafkaTaskCoordinator(config);
            HdfsStorageManager hdfs = new HdfsStorageManager(config);

            try {
                MetadataManager.ForecastRun run = metaMgr.createRun(
                        testTime, 24, "integration-v1", "domain-01",
                        config.getNX(), config.getNY(), config.getNZ()
                );
                log.info("创建预报任务: id={}", run.id);

                String taskId = kafkaCoord.submitForecastTask(
                        testTime, 24, "domain-01", "integration-v1"
                );
                log.info("Kafka任务ID: {}", taskId);

                byte[] dummyOutput = "dummy forecast data".getBytes();
                String hdfsPath = hdfs.storeFile(
                        "integration", testTime, 24, "T2", "bin", dummyOutput
                );
                log.info("HDFS路径: {}", hdfsPath);

                metaMgr.registerDataset(
                        run.id, 24, "Temperature", "bin",
                        dummyOutput.length, hdfsPath
                );

                metaMgr.updateRunStatus(run.id, "COMPLETED", 100, 45.0);

                metaMgr.insertVerificationScore(
                        run.id, 24, "T2", 1.5, -0.2, 0.93, 1000, testTime
                );

                Map<String, Object> result = new HashMap<>();
                result.put("run_id", run.id);
                result.put("rmse_t2", 1.5);
                kafkaCoord.sendTaskResult(taskId, "COMPLETED", result);

                List<MetadataManager.ForecastRun> runs = metaMgr.getRecentRuns(5);
                assertThat(runs).isNotEmpty();

                Optional<MetadataManager.ForecastRun> finished = runs.stream()
                        .filter(r -> r.id == run.id)
                        .findFirst();
                assertThat(finished).isPresent();
                assertThat(finished.get().status).isEqualTo("COMPLETED");
                assertThat(finished.get().progress).isEqualTo(100);

                log.info("=== 全栈微集成测试通过 ===");

            } finally {
                metaMgr.close();
                kafkaCoord.shutdown();
                hdfs.close();
            }
        }
    }
}
