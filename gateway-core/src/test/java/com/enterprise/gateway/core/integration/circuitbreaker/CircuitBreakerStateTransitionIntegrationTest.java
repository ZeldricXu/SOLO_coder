package com.enterprise.gateway.core.integration.circuitbreaker;

import com.enterprise.gateway.core.integration.base.GatewayIntegrationTestBase;
import com.enterprise.gateway.core.integration.base.TestJwtHelper;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerStateTransitionIntegrationTest extends GatewayIntegrationTestBase {

    private static final String TEST_ROUTE = "cb-test-route";

    @Test
    void shouldTransitionFromClosedToOpenOnConsecutiveFailures() {
        mockBackendResponseForAnyPath(500, "{\"error\":\"internal\"}");

        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", TEST_ROUTE,
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/cbtest/**\"}}]",
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

        Map<String, Object> circuitBreakerBody = Map.of(
                "routeId", TEST_ROUTE,
                "failureRateThreshold", 50.0,
                "minimumNumberOfCalls", 4,
                "slidingWindowSize", 10,
                "waitDurationInOpenState", 3000L,
                "permittedNumberOfCallsInHalfOpenState", 5,
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(circuitBreakerBody)
                .when()
                .post("/admin/circuitbreaker")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        String token = TestJwtHelper.generateValidToken();
        for (int i = 0; i < 10; i++) {
            givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/cbtest/test" + i);
        }

        Response stateResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/circuitbreaker/state/" + TEST_ROUTE);

        assertThat(stateResponse.statusCode()).isEqualTo(200);
        assertThat(stateResponse.jsonPath().getString("data")).isEqualTo("OPEN");

        Response blockedResponse = givenGateway()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/cbtest/blocked");

        assertThat(blockedResponse.statusCode()).isEqualTo(503);
    }

    @Test
    void shouldTransitionThroughHalfOpenToClosedAfterRecovery() throws InterruptedException {
        mockBackendResponseForAnyPath(500, "{\"error\":\"internal\"}");

        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", "cb-recovery-route",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/cbrecovery/**\"}}]",
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

        Map<String, Object> circuitBreakerBody = Map.of(
                "routeId", "cb-recovery-route",
                "failureRateThreshold", 50.0,
                "minimumNumberOfCalls", 4,
                "slidingWindowSize", 10,
                "waitDurationInOpenState", 3000L,
                "permittedNumberOfCallsInHalfOpenState", 5,
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(circuitBreakerBody)
                .when()
                .post("/admin/circuitbreaker")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        String token = TestJwtHelper.generateValidToken();
        for (int i = 0; i < 10; i++) {
            givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/cbrecovery/test" + i);
        }

        Response openStateResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/circuitbreaker/state/cb-recovery-route");

        assertThat(openStateResponse.jsonPath().getString("data")).isEqualTo("OPEN");

        Thread.sleep(3000);

        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        for (int i = 0; i < 5; i++) {
            givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/cbrecovery/probe" + i);
        }

        Response closedStateResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/circuitbreaker/state/cb-recovery-route");

        assertThat(closedStateResponse.jsonPath().getString("data")).isEqualTo("CLOSED");

        for (int i = 0; i < 10; i++) {
            Response normalResponse = givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/cbrecovery/normal" + i);
            assertThat(normalResponse.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void shouldReturnToOpenOnFirstProbeFailure() throws InterruptedException {
        mockBackendResponseForAnyPath(500, "{\"error\":\"internal\"}");

        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", "cb-probe-fail-route",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/cbprobefail/**\"}}]",
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

        Map<String, Object> circuitBreakerBody = Map.of(
                "routeId", "cb-probe-fail-route",
                "failureRateThreshold", 50.0,
                "minimumNumberOfCalls", 4,
                "slidingWindowSize", 10,
                "waitDurationInOpenState", 3000L,
                "permittedNumberOfCallsInHalfOpenState", 5,
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(circuitBreakerBody)
                .when()
                .post("/admin/circuitbreaker")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        String token = TestJwtHelper.generateValidToken();
        for (int i = 0; i < 10; i++) {
            givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/cbprobefail/test" + i);
        }

        Response openStateResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/circuitbreaker/state/cb-probe-fail-route");

        assertThat(openStateResponse.jsonPath().getString("data")).isEqualTo("OPEN");

        Thread.sleep(3000);

        Response probeResponse = givenGateway()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/cbprobefail/probe1");

        assertThat(probeResponse.statusCode()).isEqualTo(500);

        Response stateAfterProbeResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/circuitbreaker/state/cb-probe-fail-route");

        assertThat(stateAfterProbeResponse.jsonPath().getString("data")).isEqualTo("OPEN");

        Response nextBlockedResponse = givenGateway()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/cbprobefail/blocked");

        assertThat(nextBlockedResponse.statusCode()).isEqualTo(503);
    }

    @Test
    void shouldManuallyResetCircuitBreakerViaAdminApi() {
        mockBackendResponseForAnyPath(500, "{\"error\":\"internal\"}");

        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", "cb-reset-route",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/cbreset/**\"}}]",
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

        Map<String, Object> circuitBreakerBody = Map.of(
                "routeId", "cb-reset-route",
                "failureRateThreshold", 50.0,
                "minimumNumberOfCalls", 4,
                "slidingWindowSize", 10,
                "waitDurationInOpenState", 30000L,
                "permittedNumberOfCallsInHalfOpenState", 5,
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(circuitBreakerBody)
                .when()
                .post("/admin/circuitbreaker")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        String token = TestJwtHelper.generateValidToken();
        for (int i = 0; i < 10; i++) {
            givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/cbreset/test" + i);
        }

        Response openStateResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/circuitbreaker/state/cb-reset-route");

        assertThat(openStateResponse.jsonPath().getString("data")).isEqualTo("OPEN");

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .post("/admin/circuitbreaker/reset/cb-reset-route")
                .then()
                .statusCode(200);

        Response closedStateResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/circuitbreaker/state/cb-reset-route");

        assertThat(closedStateResponse.jsonPath().getString("data")).isEqualTo("CLOSED");

        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        Response normalResponse = givenGateway()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/cbreset/after-reset");

        assertThat(normalResponse.statusCode()).isEqualTo(200);
    }

    @Test
    void shouldIsolateCircuitBreakersBetweenRoutes() {
        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        String backendUri = mockServerUrl();

        Map<String, Object> routeABody = Map.of(
                "routeId", "cb-route-a",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/cb-a/**\"}}]",
                "matchType", "PREFIX",
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeABody)
                .when()
                .post("/admin/routes")
                .then()
                .statusCode(200);

        Map<String, Object> routeBBody = Map.of(
                "routeId", "cb-route-b",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/cb-b/**\"}}]",
                "matchType", "PREFIX",
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(routeBBody)
                .when()
                .post("/admin/routes")
                .then()
                .statusCode(200);

        Map<String, Object> cbABody = Map.of(
                "routeId", "cb-route-a",
                "failureRateThreshold", 50.0,
                "minimumNumberOfCalls", 4,
                "slidingWindowSize", 10,
                "waitDurationInOpenState", 30000L,
                "permittedNumberOfCallsInHalfOpenState", 5,
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(cbABody)
                .when()
                .post("/admin/circuitbreaker")
                .then()
                .statusCode(200);

        Map<String, Object> cbBBody = Map.of(
                "routeId", "cb-route-b",
                "failureRateThreshold", 50.0,
                "minimumNumberOfCalls", 4,
                "slidingWindowSize", 10,
                "waitDurationInOpenState", 30000L,
                "permittedNumberOfCallsInHalfOpenState", 5,
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(cbBBody)
                .when()
                .post("/admin/circuitbreaker")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        mockBackendResponseForAnyPath(500, "{\"error\":\"internal\"}");

        String token = TestJwtHelper.generateValidToken();
        for (int i = 0; i < 10; i++) {
            givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/cb-a/test" + i);
        }

        Response stateAResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/circuitbreaker/state/cb-route-a");

        assertThat(stateAResponse.jsonPath().getString("data")).isEqualTo("OPEN");

        Response stateBResponse = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .get("/admin/circuitbreaker/state/cb-route-b");

        assertThat(stateBResponse.jsonPath().getString("data")).isEqualTo("CLOSED");

        Response blockedResponseA = givenGateway()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/cb-a/blocked");

        assertThat(blockedResponseA.statusCode()).isEqualTo(503);

        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        Response okResponseB = givenGateway()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/cb-b/ok");

        assertThat(okResponseB.statusCode()).isEqualTo(200);
    }
}
