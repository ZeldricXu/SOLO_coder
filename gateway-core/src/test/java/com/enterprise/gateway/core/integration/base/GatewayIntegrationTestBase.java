package com.enterprise.gateway.core.integration.base;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockserver.client.MockServerClient;
import org.mockserver.mockserver.MockServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@ContextConfiguration(initializers = GatewayIntegrationTestBase.Initializer.class)
public abstract class GatewayIntegrationTestBase {

    @Container
    static MySQLContainer<?> mysqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("gateway_db")
            .withUsername("root")
            .withPassword("root")
            .withInitScript("sql/init.sql");

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static GenericContainer<?> elasticsearchContainer = new GenericContainer<>(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.12.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200);

    @Container
    static GenericContainer<?> nacosContainer = new GenericContainer<>(DockerImageName.parse("nacos/nacos-server:v2.3.1-slim"))
            .withEnv("MODE", "standalone")
            .withExposedPorts(8848);

    @LocalServerPort
    protected int gatewayPort;

    protected MockServerClient mockServerClient;

    private MockServer mockServer;

    private static final int MOCK_SERVER_PORT = 9090;

    static class Initializer extends org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
        @Override
        public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            org.springframework.test.context.support.TestPropertyValues.of(
                    "spring.datasource.url=" + mysqlContainer.getJdbcUrl(),
                    "spring.datasource.username=" + mysqlContainer.getUsername(),
                    "spring.datasource.password=" + mysqlContainer.getPassword(),
                    "spring.data.redis.host=" + redisContainer.getHost(),
                    "spring.data.redis.port=" + redisContainer.getMappedPort(6379),
                    "spring.elasticsearch.uris=http://" + elasticsearchContainer.getHost() + ":" + elasticsearchContainer.getMappedPort(9200),
                    "spring.elasticsearch.username=elastic",
                    "spring.elasticsearch.password=elastic",
                    "spring.cloud.nacos.discovery.server-addr=" + nacosContainer.getHost() + ":" + nacosContainer.getMappedPort(8848),
                    "spring.cloud.nacos.config.server-addr=" + nacosContainer.getHost() + ":" + nacosContainer.getMappedPort(8848)
            ).applyTo(context.getEnvironment());
        }
    }

    @BeforeEach
    void startMockServer() {
        mockServer = new MockServer(MOCK_SERVER_PORT);
        mockServer.start();
        mockServerClient = new MockServerClient("localhost", MOCK_SERVER_PORT);
    }

    @AfterEach
    void stopMockServer() {
        if (mockServerClient != null) {
            mockServerClient.reset();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    protected String gatewayBaseUrl() {
        return "http://localhost:" + gatewayPort;
    }

    protected String mockServerUrl() {
        return "http://localhost:" + MOCK_SERVER_PORT;
    }

    protected RequestSpecification givenGateway() {
        return RestAssured.given()
                .baseUri(gatewayBaseUrl())
                .log().all();
    }

    protected void mockBackendResponse(String path, int statusCode, String responseBody) {
        mockServerClient
                .when(request().withMethod("GET").withPath(path))
                .respond(response().withStatusCode(statusCode)
                        .withBody(responseBody, org.mockserver.model.MediaType.APPLICATION_JSON_UTF_8));
    }

    protected void mockBackendResponseForAnyPath(int statusCode, String responseBody) {
        mockServerClient
                .when(request())
                .respond(response().withStatusCode(statusCode)
                        .withBody(responseBody, org.mockserver.model.MediaType.APPLICATION_JSON_UTF_8));
    }
}
