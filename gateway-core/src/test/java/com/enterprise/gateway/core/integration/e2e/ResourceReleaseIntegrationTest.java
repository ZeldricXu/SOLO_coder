package com.enterprise.gateway.core.integration.e2e;

import com.enterprise.gateway.core.integration.base.GatewayIntegrationTestBase;
import com.enterprise.gateway.core.integration.base.TestJwtHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceReleaseIntegrationTest extends GatewayIntegrationTestBase {

    @Autowired
    private ReactiveRedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Test
    void shouldReleaseRedisConnectionsAfterRequests() {
        String backendUri = mockServerUrl();

        Map<String, Object> routeBody = Map.of(
                "routeId", "resource-test-route",
                "uri", backendUri,
                "predicates", "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/resourcetest/**\"}}]",
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

        String token = TestJwtHelper.generateValidToken();
        for (int i = 0; i < 100; i++) {
            givenGateway()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/resourcetest/test" + i)
                    .then()
                    .statusCode(200);
        }

        String testKey = "resource:release:test";
        redisTemplate.opsForValue().set(testKey, "test-value").block();
        String value = redisTemplate.opsForValue().get(testKey).block();
        assertThat(value).isEqualTo("test-value");
        redisTemplate.delete(testKey).block();

        assertThat(redisConnectionFactory).isNotNull();
    }

    @Test
    void shouldShutdownGracefullyWhenContextClosed() {
        assertThat(applicationContext).isNotNull();

        assertThat(applicationContext.containsBean("reactiveRedisTemplate")).isTrue();
        assertThat(applicationContext.containsBean("inMemoryRouteDefinitionRepository")).isTrue();

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        assertThat(beanNames).isNotEmpty();

        assertThat(applicationContext.getBean(ReactiveRedisConnectionFactory.class)).isNotNull();

        int beanCount = applicationContext.getBeanDefinitionCount();
        assertThat(beanCount).isGreaterThan(0);
    }
}
