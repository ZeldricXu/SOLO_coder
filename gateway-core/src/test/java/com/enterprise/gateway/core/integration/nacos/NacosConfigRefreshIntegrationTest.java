package com.enterprise.gateway.core.integration.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.enterprise.gateway.core.integration.base.GatewayIntegrationTestBase;
import com.enterprise.gateway.core.integration.base.TestJwtHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Disabled("Nacos container takes too long for CI, enable for manual testing")
class NacosConfigRefreshIntegrationTest extends GatewayIntegrationTestBase {

    @Autowired
    private ConfigService configService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRefreshRoutesWhenNacosConfigChanges() throws Exception {
        String backendUri = mockServerUrl();

        List<Map<String, Object>> routes = List.of(
                Map.of(
                        "routeId", "nacos-test-route",
                        "uri", backendUri,
                        "predicates", List.of(Map.of(
                                "name", "Path",
                                "args", Map.of("pattern", "/api/nacostest/**")
                        )),
                        "matchType", "PREFIX",
                        "status", 1
                )
        );

        String configJson = toJson(routes);

        configService.publishConfig("gateway-routes", "DEFAULT_GROUP", configJson);

        Thread.sleep(5000);

        mockBackendResponseForAnyPath(200, "{\"nacos\":true}");

        var response = givenGateway()
                .header("Authorization", "Bearer " + TestJwtHelper.generateValidToken())
                .when()
                .get("/api/nacostest/test");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private String toJson(Object obj) throws JsonProcessingException {
        return objectMapper.writeValueAsString(obj);
    }
}
