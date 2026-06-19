package com.enterprise.gateway.routing.loader;

import com.enterprise.gateway.common.model.RouteDefinition;
import com.enterprise.gateway.routing.DynamicRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseRouteLoader {

    private final DynamicRouteService dynamicRouteService;

    @PostConstruct
    public void loadRoutesFromDatabase() {
        try {
            List<RouteDefinition> entities = queryAllRoutes();
            List<org.springframework.cloud.gateway.route.RouteDefinition> definitions = new ArrayList<>();
            for (RouteDefinition entity : entities) {
                if (entity.getStatus() != null && entity.getStatus() == 1) {
                    definitions.add(dynamicRouteService.convertToGatewayRoute(entity));
                }
            }
            dynamicRouteService.refreshRoutes(definitions);
            log.info("Loaded {} active routes from database", definitions.size());
        } catch (Exception e) {
            log.error("Failed to load routes from database", e);
        }
    }

    protected List<RouteDefinition> queryAllRoutes() {
        return new ArrayList<>();
    }
}
