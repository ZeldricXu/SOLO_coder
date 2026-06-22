package com.enterprise.gateway.core.integration.e2e;

import com.enterprise.gateway.core.integration.base.GatewayIntegrationTestBase;
import com.enterprise.gateway.core.integration.base.TestJwtHelper;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AdminApiConcurrentModificationTest extends GatewayIntegrationTestBase {

    @Test
    void shouldHandleConcurrentRouteUpdates() throws InterruptedException {
        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", "concurrent-route",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/concurrent/**\"}}]",
                "matchType", "PREFIX",
                "status", 1,
                "metadata", "{\"version\":\"initial\"}"
        );

        Response createResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeBody)
                .when()
                .post("/admin/routes");

        assertThat(createResponse.statusCode()).isEqualTo(200);
        Long routeId = createResponse.jsonPath().getLong("data.id");

        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int version = i;
            executorService.submit(() -> {
                try {
                    Map<String, Object> updateBody = Map.of(
                            "routeId", "concurrent-route",
                            "uri", backendUri,
                            "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/concurrent/**\"}}]",
                            "matchType", "PREFIX",
                            "status", 1,
                            "metadata", "{\"version\":\"v" + version + "\"}"
                    );

                    Response response = givenGateway()
                            .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                            .contentType("application/json")
                            .body(updateBody)
                            .when()
                            .put("/admin/routes/" + routeId);

                    if (response.statusCode() == 200) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(successCount.get()).isGreaterThan(0);

        Response getResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/routes/" + routeId);

        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(getResponse.jsonPath().getString("data.routeId")).isEqualTo("concurrent-route");
        assertThat(getResponse.jsonPath().getString("data.uri")).isNotNull();
        assertThat(getResponse.jsonPath().getString("data.uri")).isEqualTo(backendUri);
    }

    @Test
    void shouldHandleConcurrentRateLimitRuleUpdates() throws InterruptedException {
        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", "concurrent-rl-route",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/concurrent-rl/**\"}}]",
                "matchType", "PREFIX",
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeBody)
                .when()
                .post("/admin/routes")
                .then()
                .statusCode(200);

        Map<String, Object> rateLimitBody = Map.of(
                "routeId", "concurrent-rl-route",
                "strategy", "TOKEN_BUCKET",
                "capacity", 100L,
                "refillRate", 10L,
                "windowSize", 60L,
                "permits", 100L,
                "status", 1
        );

        Response createResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(rateLimitBody)
                .when()
                .post("/admin/ratelimit");

        assertThat(createResponse.statusCode()).isEqualTo(200);
        Long ruleId = createResponse.jsonPath().getLong("data.id");

        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Long> capacities = new ArrayList<>();
        capacities.add(50L);
        capacities.add(75L);
        capacities.add(120L);
        capacities.add(200L);
        capacities.add(150L);

        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    Map<String, Object> updateBody = Map.of(
                            "routeId", "concurrent-rl-route",
                            "strategy", "TOKEN_BUCKET",
                            "capacity", capacities.get(index),
                            "refillRate", 10L,
                            "windowSize", 60L,
                            "permits", capacities.get(index),
                            "status", 1
                    );

                    Response response = givenGateway()
                            .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                            .contentType("application/json")
                            .body(updateBody)
                            .when()
                            .put("/admin/ratelimit/" + ruleId);

                    if (response.statusCode() == 200) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(successCount.get()).isGreaterThan(0);

        Response getResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/ratelimit/concurrent-rl-route");

        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(getResponse.jsonPath().getString("data.routeId")).isEqualTo("concurrent-rl-route");
        assertThat(getResponse.jsonPath().getLong("data.capacity")).isNotNull();
        assertThat(getResponse.jsonPath().getLong("data.capacity")).isGreaterThan(0);
    }
}
