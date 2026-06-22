package com.enterprise.gateway.core.integration.e2e;

import com.enterprise.gateway.admin.mapper.IpRuleMapper;
import com.enterprise.gateway.common.model.IpRule;
import com.enterprise.gateway.core.integration.GatewayIntegrationTestBase;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class FullRequestChainIntegrationTest extends GatewayIntegrationTestBase {

    @Autowired
    private IpRuleMapper ipRuleMapper;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @BeforeEach
    void resetMocks() {
        mockServerClient.reset();
    }

    @Test
    void shouldCreateRouteAndForwardRequest() {
        String backendUri = "http://localhost:" + mockServerPort;

        Map<String, Object> routeBody = Map.of(
                "routeId", "user-service",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/user/**\"}}]",
                "matchType", "PREFIX",
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeBody)
                .when()
                .post("/admin/routes")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        mockBackendResponse("/api/user/list", 200, "{\"users\":[]}");

        Response response = givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateValidToken())
                .when()
                .get("/api/user/list");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("code")).isEqualTo(200);
        assertThat(response.jsonPath().getString("message")).isEqualTo("success");
        assertThat(response.jsonPath().getString("data")).isNotNull();
        assertThat(response.getHeader("X-Trace-Id")).isNotNull();
        assertThat(response.getHeader("X-RateLimit-Limit")).isNotNull();
    }

    @Test
    void shouldReturn429WhenRateLimitExceeded() {
        String backendUri = "http://localhost:" + mockServerPort;

        Map<String, Object> routeBody = Map.of(
                "routeId", "ratelimit-service",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/ratelimit/**\"}}]",
                "matchType", "PREFIX",
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeBody)
                .when()
                .post("/admin/routes")
                .then()
                .statusCode(200);

        Map<String, Object> rateLimitBody = Map.of(
                "routeId", "ratelimit-service",
                "strategy", "TOKEN_BUCKET",
                "capacity", 5,
                "refillRate", 1,
                "windowSize", 60,
                "permits", 5,
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(rateLimitBody)
                .when()
                .post("/admin/ratelimit")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        String token = testJwtHelper.generateValidToken();
        for (int i = 0; i < 5; i++) {
            givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/ratelimit/test")
                    .then()
                    .statusCode(200);
        }

        Response rejectedResponse = givenGateway()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/ratelimit/test");

        assertThat(rejectedResponse.statusCode()).isEqualTo(429);
        assertThat(rejectedResponse.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void shouldRejectRequestWithoutValidJwt() {
        String backendUri = "http://localhost:" + mockServerPort;

        Map<String, Object> routeBody = Map.of(
                "routeId", "auth-service",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/protected/**\"}}]",
                "matchType", "PREFIX",
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeBody)
                .when()
                .post("/admin/routes")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        Response noTokenResponse = givenGateway()
                .when()
                .get("/api/protected/resource");

        assertThat(noTokenResponse.statusCode()).isEqualTo(401);

        Response expiredTokenResponse = givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateExpiredToken())
                .when()
                .get("/api/protected/resource");

        assertThat(expiredTokenResponse.statusCode()).isEqualTo(401);

        Response validTokenResponse = givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateValidToken())
                .when()
                .get("/api/protected/resource");

        assertThat(validTokenResponse.statusCode()).isEqualTo(200);
    }

    @Test
    void shouldBlockBlacklistedIp() {
        String backendUri = "http://localhost:" + mockServerPort;

        Map<String, Object> routeBody = Map.of(
                "routeId", "ip-block-service",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/ipblock/**\"}}]",
                "matchType", "PREFIX",
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeBody)
                .when()
                .post("/admin/routes")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        IpRule ipRule = IpRule.builder()
                .routeId("ip-block-service")
                .ipOrCidr("10.0.0.99")
                .ruleType("BLACKLIST")
                .build();
        ipRuleMapper.insert(ipRule);

        redisTemplate.opsForValue()
                .set("ip:blacklist", "{\"ips\":[\"10.0.0.99\"]}")
                .block();

        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        Response response = givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateValidToken())
                .header("X-Forwarded-For", "10.0.0.99")
                .when()
                .get("/api/ipblock/resource");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void shouldWrapResponseInUnifiedFormat() {
        String backendUri = "http://localhost:" + mockServerPort;

        Map<String, Object> routeBody = Map.of(
                "routeId", "wrap-service",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/wrap/**\"}}]",
                "matchType", "PREFIX",
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeBody)
                .when()
                .post("/admin/routes")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        mockBackendResponse("/api/wrap/item", 200, "{\"id\":1,\"name\":\"test\"}");

        Response response = givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateValidToken())
                .when()
                .get("/api/wrap/item");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("code")).isEqualTo(200);
        assertThat(response.jsonPath().getString("message")).isEqualTo("success");
        assertThat(response.jsonPath().getMap("data")).containsEntry("id", 1);
        assertThat(response.jsonPath().getMap("data")).containsEntry("name", "test");
        assertThat(response.jsonPath().getLong("timestamp")).isNotNull();
    }

    @Test
    void shouldWriteAccessLogToElasticsearch() throws InterruptedException {
        String backendUri = "http://localhost:" + mockServerPort;

        Map<String, Object> routeBody = Map.of(
                "routeId", "log-service",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/logtest/**\"}}]",
                "matchType", "PREFIX",
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeBody)
                .when()
                .post("/admin/routes")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        mockBackendResponse("/api/logtest/ping", 200, "{\"pong\":true}");

        givenGateway()
                .header("Authorization", "Bearer " + testJwtHelper.generateValidToken())
                .when()
                .get("/api/logtest/ping")
                .then()
                .statusCode(200);

        Thread.sleep(2000);

        String indexName = "gateway-access-logs-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        Criteria criteria = new Criteria("path").is("/api/logtest/ping")
                .and(new Criteria("method").is("GET"));
        Query query = new CriteriaQuery(criteria);

        SearchHits<Map> hits = elasticsearchOperations.search(query, Map.class,
                org.springframework.data.elasticsearch.core.IndexCoordinates.of(indexName));

        assertThat(hits.getTotalHits()).isGreaterThan(0);

        Map<String, Object> logEntry = hits.getSearchHit(0).getContent();
        assertThat(logEntry).containsEntry("method", "GET");
        assertThat(logEntry).containsEntry("path", "/api/logtest/ping");
        assertThat(logEntry).containsEntry("status", 200);
    }
}
