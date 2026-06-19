package com.enterprise.gateway.routing;

import com.enterprise.gateway.common.model.RouteDefinition;
import com.enterprise.gateway.common.util.JacksonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicRouteService {

    private final InMemoryRouteDefinitionRepository routeDefinitionRepository;
    private final RouteMapper routeMapper;

    public void refreshAll() {
        List<RouteDefinition> entities = routeMapper.selectList(new LambdaQueryWrapper<RouteDefinition>()
                .eq(RouteDefinition::getStatus, 1));
        List<org.springframework.cloud.gateway.route.RouteDefinition> definitions = new ArrayList<>();
        for (RouteDefinition entity : entities) {
            definitions.add(convertToGatewayRoute(entity));
        }
        refreshRoutes(definitions);
    }

    public void refreshRoutes(List<org.springframework.cloud.gateway.route.RouteDefinition> definitions) {
        routeDefinitionRepository.getRouteDefinitions()
                .collectList()
                .flatMap(existing -> {
                    List<Mono<Void>> deletions = new ArrayList<>();
                    for (org.springframework.cloud.gateway.route.RouteDefinition def : existing) {
                        deletions.add(routeDefinitionRepository.delete(Mono.just(def.getId())));
                    }
                    return Flux.concat(deletions).then();
                })
                .then(Mono.defer(() -> {
                    List<Mono<Void>> additions = new ArrayList<>();
                    for (org.springframework.cloud.gateway.route.RouteDefinition def : definitions) {
                        additions.add(routeDefinitionRepository.save(Mono.just(def)));
                    }
                    return Flux.concat(additions).then();
                }))
                .subscribe(
                        null,
                        error -> log.error("Failed to refresh routes", error),
                        () -> log.info("Routes refreshed successfully, count: {}", definitions.size())
                );
    }

    public void addRoute(org.springframework.cloud.gateway.route.RouteDefinition definition) {
        routeDefinitionRepository.save(Mono.just(definition))
                .subscribe(
                        null,
                        error -> log.error("Failed to add route: {}", definition.getId(), error),
                        () -> log.info("Route added: {}", definition.getId())
                );
    }

    public void updateRoute(org.springframework.cloud.gateway.route.RouteDefinition definition) {
        deleteRoute(definition.getId());
        addRoute(definition);
    }

    public void deleteRoute(String routeId) {
        routeDefinitionRepository.delete(Mono.just(routeId))
                .subscribe(
                        null,
                        error -> log.error("Failed to delete route: {}", routeId, error),
                        () -> log.info("Route deleted: {}", routeId)
                );
    }

    public org.springframework.cloud.gateway.route.RouteDefinition convertToGatewayRoute(RouteDefinition entity) {
        org.springframework.cloud.gateway.route.RouteDefinition routeDef = new org.springframework.cloud.gateway.route.RouteDefinition();
        routeDef.setId(entity.getRouteId());
        routeDef.setUri(URI.create(entity.getUri()));
        routeDef.setOrder(entity.getOrderNum() != null ? entity.getOrderNum() : 0);

        if (entity.getPredicates() != null) {
            Map<String, Object> predicateMap = JacksonUtil.toMap(entity.getPredicates());
            org.springframework.cloud.gateway.handler.predicate.PredicateDefinition predicateDefinition =
                    new org.springframework.cloud.gateway.handler.predicate.PredicateDefinition();
            predicateDefinition.setName(predicateMap.getOrDefault("name", "Path").toString());
            Object args = predicateMap.get("args");
            if (args instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> argsMap = (Map<String, String>) args;
                predicateDefinition.setArgs(new LinkedHashMap<>(argsMap));
            }
            routeDef.setPredicates(List.of(predicateDefinition));
        }

        if (entity.getFilters() != null) {
            Map<String, Object> filterMap = JacksonUtil.toMap(entity.getFilters());
            org.springframework.cloud.gateway.filter.FilterDefinition filterDefinition =
                    new org.springframework.cloud.gateway.filter.FilterDefinition();
            filterDefinition.setName(filterMap.getOrDefault("name", "").toString());
            Object args = filterMap.get("args");
            if (args instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> argsMap = (Map<String, String>) args;
                filterDefinition.setArgs(new LinkedHashMap<>(argsMap));
            }
            routeDef.setFilters(List.of(filterDefinition));
        }

        if (entity.getMetadata() != null) {
            Map<String, Object> metadataMap = JacksonUtil.toMap(entity.getMetadata());
            Map<String, Object> metadata = new HashMap<>(metadataMap);
            if (entity.getWeight() != null) {
                metadata.put("weight", entity.getWeight());
            }
            routeDef.setMetadata(metadata);
        } else if (entity.getWeight() != null) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("weight", entity.getWeight());
            routeDef.setMetadata(metadata);
        }

        return routeDef;
    }
}
