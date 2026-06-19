package com.enterprise.gateway.routing.loader;

import com.alibaba.nacos.api.config.annotation.NacosConfigListener;
import com.enterprise.gateway.routing.DynamicRouteService;
import com.enterprise.gateway.common.model.RouteDefinition;
import com.enterprise.gateway.common.util.JacksonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NacosRouteLoader {

    private final DynamicRouteService dynamicRouteService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        log.info("NacosRouteLoader initialized, waiting for config from gateway-routes.json");
    }

    @NacosConfigListener(dataId = "gateway-routes.json")
    public void onRouteConfigChange(String config) {
        try {
            List<Map<String, Object>> routeMaps = objectMapper.readValue(config,
                    new TypeReference<List<Map<String, Object>>>() {});

            List<org.springframework.cloud.gateway.route.RouteDefinition> definitions = new ArrayList<>();
            for (Map<String, Object> routeMap : routeMaps) {
                RouteDefinition entity = mapToEntity(routeMap);
                definitions.add(dynamicRouteService.convertToGatewayRoute(entity));
            }
            dynamicRouteService.refreshRoutes(definitions);
            log.info("Loaded {} routes from Nacos config change", definitions.size());
        } catch (Exception e) {
            log.error("Failed to parse route config from Nacos", e);
        }
    }

    @SuppressWarnings("unchecked")
    private RouteDefinition mapToEntity(Map<String, Object> map) {
        return RouteDefinition.builder()
                .routeId((String) map.get("routeId"))
                .uri((String) map.get("uri"))
                .predicates(map.get("predicates") != null ? JacksonUtil.toJson(map.get("predicates")) : null)
                .filters(map.get("filters") != null ? JacksonUtil.toJson(map.get("filters")) : null)
                .metadata(map.get("metadata") != null ? JacksonUtil.toJson(map.get("metadata")) : null)
                .orderNum(map.get("orderNum") != null ? ((Number) map.get("orderNum")).intValue() : 0)
                .weight(map.get("weight") != null ? ((Number) map.get("weight")).intValue() : null)
                .matchType((String) map.get("matchType"))
                .build();
    }
}
