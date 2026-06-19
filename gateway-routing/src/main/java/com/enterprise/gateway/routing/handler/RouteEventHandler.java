package com.enterprise.gateway.routing.handler;

import com.enterprise.gateway.routing.DynamicRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RouteEventHandler implements ApplicationListener<RouteEventHandler.RouteChangeEvent> {

    private final DynamicRouteService dynamicRouteService;

    @Override
    public void onApplicationEvent(RouteChangeEvent event) {
        log.info("Received route change event, action: {}", event.getAction());
        switch (event.getAction()) {
            case REFRESH -> {
                List<RouteDefinition> definitions = event.getDefinitions();
                if (definitions != null) {
                    dynamicRouteService.refreshRoutes(definitions);
                }
            }
            case ADD -> {
                if (event.getDefinition() != null) {
                    dynamicRouteService.addRoute(event.getDefinition());
                }
            }
            case UPDATE -> {
                if (event.getDefinition() != null) {
                    dynamicRouteService.updateRoute(event.getDefinition());
                }
            }
            case DELETE -> {
                if (event.getRouteId() != null) {
                    dynamicRouteService.deleteRoute(event.getRouteId());
                }
            }
        }
    }

    public enum RouteAction {
        REFRESH, ADD, UPDATE, DELETE
    }

    public static class RouteChangeEvent extends org.springframework.context.ApplicationEvent {

        private final RouteAction action;
        private List<RouteDefinition> definitions;
        private RouteDefinition definition;
        private String routeId;

        public RouteChangeEvent(Object source, RouteAction action) {
            super(source);
            this.action = action;
        }

        public RouteChangeEvent(Object source, RouteAction action, List<RouteDefinition> definitions) {
            super(source);
            this.action = action;
            this.definitions = definitions;
        }

        public RouteChangeEvent(Object source, RouteAction action, RouteDefinition definition) {
            super(source);
            this.action = action;
            this.definition = definition;
        }

        public RouteChangeEvent(Object source, RouteAction action, String routeId) {
            super(source);
            this.action = action;
            this.routeId = routeId;
        }

        public RouteAction getAction() {
            return action;
        }

        public List<RouteDefinition> getDefinitions() {
            return definitions;
        }

        public RouteDefinition getDefinition() {
            return definition;
        }

        public String getRouteId() {
            return routeId;
        }
    }
}
