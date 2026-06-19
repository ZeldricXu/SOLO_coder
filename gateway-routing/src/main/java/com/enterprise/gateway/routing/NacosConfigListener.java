package com.enterprise.gateway.routing;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.enterprise.gateway.common.model.RouteDefinition;
import com.enterprise.gateway.common.util.JacksonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class NacosConfigListener {

    private final ConfigService configService;
    private final DynamicRouteService dynamicRouteService;
    private final ObjectMapper objectMapper;

    private static final String DATA_ID = "gateway-routes";
    private static final String GROUP_ID = "DEFAULT_GROUP";
    private static final long TIMEOUT_MS = 5000;

    @PostConstruct
    public void init() {
        try {
            String config = configService.getConfig(DATA_ID, GROUP_ID, TIMEOUT_MS);
            if (config != null) {
                processConfig(config);
            }

            configService.addListener(DATA_ID, GROUP_ID, new Listener() {
                @Override
                public Executor getExecutor() {
                    return Executors.newSingleThreadExecutor();
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    if (configInfo != null) {
                        processConfig(configInfo);
                    }
                }
            });

            log.info("NacosConfigListener registered for dataId: {}, groupId: {}", DATA_ID, GROUP_ID);
        } catch (Exception e) {
            log.error("Failed to initialize NacosConfigListener", e);
        }
    }

    private void processConfig(String config) {
        try {
            List<Map<String, Object>> routeMaps = objectMapper.readValue(config,
                    new TypeReference<List<Map<String, Object>>>() {});

            List<org.springframework.cloud.gateway.route.RouteDefinition> definitions = new ArrayList<>();
            for (Map<String, Object> routeMap : routeMaps) {
                RouteDefinition entity = mapToEntity(routeMap);
                definitions.add(dynamicRouteService.convertToGatewayRoute(entity));
            }
            dynamicRouteService.refreshRoutes(definitions);
            log.info("Processed {} routes from Nacos config", definitions.size());
        } catch (Exception e) {
            log.error("Failed to process Nacos config", e);
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
