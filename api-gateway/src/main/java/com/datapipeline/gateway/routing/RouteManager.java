package com.datapipeline.gateway.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RouteManager {

    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Map<String, List<Route>> routesByPath = new ConcurrentHashMap<>();

    public void registerRoute(Route route) {
        routes.put(route.getId(), route);
        routesByPath.computeIfAbsent(route.getPath(), k -> new ArrayList<>()).add(route);
        log.info("Route registered: id={}, path={}, target={}",
                route.getId(), route.getPath(), route.getTargetUrl());
    }

    public void unregisterRoute(String routeId) {
        Route route = routes.remove(routeId);
        if (route != null) {
            List<Route> pathRoutes = routesByPath.get(route.getPath());
            if (pathRoutes != null) {
                pathRoutes.removeIf(r -> r.getId().equals(routeId));
            }
            log.info("Route unregistered: id={}", routeId);
        }
    }

    public Optional<Route> matchRoute(String path, String method) {
        for (Map.Entry<String, List<Route>> entry : routesByPath.entrySet()) {
            if (pathMatches(entry.getKey(), path)) {
                for (Route route : entry.getValue()) {
                    if (route.getMethods().isEmpty() || route.getMethods().contains(method)) {
                        return Optional.of(route);
                    }
                }
            }
        }
        return Optional.empty();
    }

    public Map<String, Route> getAllRoutes() {
        return new HashMap<>(routes);
    }

    private boolean pathMatches(String pattern, String path) {
        if (pattern.equals(path)) {
            return true;
        }
        if (pattern.endsWith("**")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix);
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            if (!path.startsWith(prefix)) {
                return false;
            }
            String remaining = path.substring(prefix.length());
            return !remaining.contains("/");
        }
        return false;
    }

}
