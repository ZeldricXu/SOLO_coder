package com.cardgame.server;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.config.KafkaConfig;
import com.cardgame.common.config.MysqlConfig;
import com.cardgame.common.config.RedisConfig;
import com.cardgame.server.config.SpringConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(classes = SpringConfig.class)
@ContextConfiguration(initializers = AbstractIntegrationTest.Initializer.class)
public abstract class AbstractIntegrationTest {

    private static RedisServer redisServer;
    private static int redisPort;

    @Container
    private static final MySQLContainer<?> mysqlContainer = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.0.36")
    )
            .withDatabaseName("cardgame")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
            .waitingFor(Wait.forLogMessage(".*ready for connections.*", 2))
            .withStartupTimeoutSeconds(120);

    @Container
    private static final KafkaContainer kafkaContainer = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1")
    )
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
            .withStartupTimeoutSeconds(120);

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ConfigurableEnvironment environment;

    @BeforeAll
    static void startContainers() {
        log.info("Starting Testcontainers for integration tests...");

        try {
            redisPort = findAvailablePort(6379, 6380, 6381, 6382, 6383);
            log.info("Starting embedded Redis on port: {}", redisPort);
            redisServer = new RedisServer(redisPort);
            redisServer.start();
            log.info("Embedded Redis started successfully");
        } catch (Exception e) {
            log.error("Failed to start embedded Redis", e);
            throw new RuntimeException("Failed to start embedded Redis", e);
        }
    }

    @AfterAll
    static void stopContainers() {
        log.info("Stopping containers...");

        if (redisServer != null && redisServer.isActive()) {
            redisServer.stop();
            log.info("Embedded Redis stopped");
        }
    }

    private static int findAvailablePort(int... portsToTry) {
        for (int port : portsToTry) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return port;
            } catch (IOException ignored) {
            }
        }
        throw new RuntimeException("No available port found for Redis");
    }

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            ConfigurableEnvironment environment = applicationContext.getEnvironment();

            Map<String, Object> properties = new HashMap<>();

            String jdbcUrl = mysqlContainer.getJdbcUrl();
            String username = mysqlContainer.getUsername();
            String password = mysqlContainer.getPassword();

            properties.put("mysql.url", jdbcUrl);
            properties.put("mysql.username", username);
            properties.put("mysql.password", password);
            properties.put("mysql.driver-class-name", mysqlContainer.getDriverClassName());

            properties.put("redis.host", "127.0.0.1");
            properties.put("redis.port", redisPort);
            properties.put("redis.password", "");
            properties.put("redis.database", 0);

            String kafkaBootstrapServers = kafkaContainer.getBootstrapServers();
            properties.put("kafka.bootstrap-servers", kafkaBootstrapServers);

            log.info("Test MySQL URL: {}", jdbcUrl);
            log.info("Test Redis port: {}", redisPort);
            log.info("Test Kafka bootstrap: {}", kafkaBootstrapServers);

            environment.getPropertySources().addFirst(
                    new MapPropertySource("testcontainers", properties)
            );

            MysqlConfig mysqlConfig = applicationContext.getBean(MysqlConfig.class);
            mysqlConfig.setUrl(jdbcUrl);
            mysqlConfig.setUsername(username);
            mysqlConfig.setPassword(password);
            mysqlConfig.setDriverClassName(mysqlContainer.getDriverClassName());

            RedisConfig redisConfig = applicationContext.getBean(RedisConfig.class);
            redisConfig.setHost("127.0.0.1");
            redisConfig.setPort(redisPort);
            redisConfig.setPassword("");
            redisConfig.setDatabase(0);

            KafkaConfig kafkaConfig = applicationContext.getBean(KafkaConfig.class);
            kafkaConfig.setBootstrapServers(kafkaBootstrapServers);

            GameConfig gameConfig = applicationContext.getBean(GameConfig.class);
            gameConfig.setMaxPlayersPerRoom(4);
            gameConfig.setMaxHandSize(10);
            gameConfig.setDefaultDrawPerTurn(5);
            gameConfig.setDefaultMaxEnergy(3);
            gameConfig.setBasePlayerHp(80);
            gameConfig.setBasePlayerSpeed(10);
            gameConfig.setReconnectTimeoutSeconds(60);
            gameConfig.setMaxMatchQueueSize(100);
            gameConfig.setMatchTimeoutSeconds(30);
        }
    }

    protected void executeSqlScript(String sql) {
        String[] statements = sql.split(";");
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                jdbcTemplate.execute(trimmed);
            }
        }
    }
}
