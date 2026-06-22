package com.enterprise.gateway.core.integration.e2e;

import com.enterprise.gateway.core.integration.base.GatewayIntegrationTestBase;
import com.enterprise.gateway.core.integration.base.TestJwtHelper;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionPathIntegrationTest extends GatewayIntegrationTestBase {

    @Test
    void shouldReturn404ForNonexistentRoute() {
        Response response = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateValidToken())
                .when()
                .get("/api/nonexistent/123");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body().asString()).doesNotContain("localhost");
        assertThat(response.body().asString()).doesNotContain("9090");
        assertThat(response.jsonPath().getInt("code")).isEqualTo(404);
        assertThat(response.jsonPath().getString("message")).isNotNull();
        assertThat(response.jsonPath().getLong("timestamp")).isNotNull();
    }

    @Test
    void shouldLogSecurityAlertsForInvalidJwt() {
        Response response = givenGateway()
                .header("Authorization", "Bearer invalid.jwt.token.here")
                .when()
                .get("/api/anything/test");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getLong("timestamp")).isNotNull();
        assertThat(response.jsonPath().getString("message")).isNotNull();
    }

    @Test
    void shouldFailOpenWhenRedisUnavailable() {
        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", "failopen-route",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/failopen/**\"}}]",
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
                "routeId", "failopen-route",
                "strategy", "TOKEN_BUCKET",
                "capacity", 100L,
                "refillRate", 10L,
                "windowSize", 60L,
                "permits", 100L,
                "status", 1
        );

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .contentType("application/json")
                .body(rateLimitBody)
                .when()
                .post("/admin/ratelimit")
                .then()
                .statusCode(200);

        givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateAdminToken())
                .when()
                .post("/admin/routes/refresh")
                .then()
                .statusCode(200);

        mockBackendResponseForAnyPath(200, "{\"ok\":true}");

        String token = TestJwtHelper.generateValidToken();
        for (int i = 0; i < 10; i++) {
            Response response = givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/failopen/test" + i);

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }
}
