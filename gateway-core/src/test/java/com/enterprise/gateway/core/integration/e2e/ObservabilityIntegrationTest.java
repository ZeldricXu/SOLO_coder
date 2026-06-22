package com.enterprise.gateway.core.integration.e2e;

import com.enterprise.gateway.core.integration.base.GatewayIntegrationTestBase;
import com.enterprise.gateway.core.integration.base.TestJwtHelper;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityIntegrationTest extends GatewayIntegrationTestBase {

    @Test
    void shouldExposePrometheusMetrics() {
        Response response = givenGateway()
                .when()
                .get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().asString()).contains("gateway_requests_count");
        assertThat(response.body().asString()).contains("gateway_requests_latency");
        assertThat(response.body().asString()).contains("http_server_requests");
    }

    @Test
    void shouldIncrementRequestCounters() {
        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", "metrics-test-route",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/metricstest/**\"}}]",
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

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        Response initialMetricsResponse = givenGateway()
                .when()
                .get("/actuator/prometheus");

        String initialBody = initialMetricsResponse.body().asString();
        double initialCount = extractMetricValue(initialBody, "gateway_requests_count");

        String token = TestJwtHelper.generateValidToken();
        for (int i = 0; i < 5; i++) {
            givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/metricstest/test" + i)
                    .then()
                    .statusCode(200);
        }

        Response updatedMetricsResponse = givenGateway()
                .when()
                .get("/actuator/prometheus");

        String updatedBody = updatedMetricsResponse.body().asString();
        double updatedCount = extractMetricValue(updatedBody, "gateway_requests_count");

        assertThat(updatedCount).isGreaterThanOrEqualTo(initialCount + 5);
    }

    @Test
    void shouldPropagateTraceIdInResponse() {
        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", "trace-test-route",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/tracetest/**\"}}]",
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

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        Response response = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateValidToken())
                .when()
                .get("/api/tracetest/test");

        assertThat(response.getHeader("X-Trace-Id")).isNotNull();
        assertThat(response.getHeader("X-Trace-Id")).isNotEmpty();
        assertThat(response.getHeader("X-Span-Id")).isNotNull();
        assertThat(response.getHeader("X-Span-Id")).isNotEmpty();
    }

    private double extractMetricValue(String metricsBody, String metricName) {
        String[] lines = metricsBody.split("\n");
        for (String line : lines) {
            if (line.startsWith(metricName + "{")) {
                int lastSpace = line.lastIndexOf(" ");
                if (lastSpace > 0) {
                    String valueStr = line.substring(lastSpace + 1).trim();
                    try {
                        return Double.parseDouble(valueStr);
                    } catch (NumberFormatException e) {
                        return 0.0;
                    }
                }
            }
        }
        return 0.0;
    }
}
