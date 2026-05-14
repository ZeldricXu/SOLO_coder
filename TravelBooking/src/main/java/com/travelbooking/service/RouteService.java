package com.travelbooking.service;

import com.travelbooking.config.RouteTypeConfig;
import com.travelbooking.dto.RouteSearchItem;
import com.travelbooking.dto.RouteSearchRequest;
import com.travelbooking.dto.RouteSearchResponse;
import com.travelbooking.exception.BusinessException;
import com.travelbooking.model.Route;
import com.travelbooking.repository.RouteRepository;
import com.travelbooking.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RouteService {

    private final RouteRepository routeRepository;
    private final AnalyticsService analyticsService;
    private final HistoryService historyService;
    private final RouteTypeConfig routeTypeConfig;

    @Transactional
    public Route createRoute(Route route) {
        validateRouteType(route);
        applyTypeDefaults(route);
        
        if (route.getRouteId() == null || route.getRouteId().isEmpty()) {
            route.setRouteId(IdGenerator.generateRouteId());
        }
        if (route.getRouteStatus() == null) {
            route.setRouteStatus("available");
        }
        if (route.getRouteAvailable() == null) {
            route.setRouteAvailable(route.getRouteQuota());
        }
        if (route.getCreatedAt() == null) {
            route.setCreatedAt(Instant.now());
        }
        
        Route saved = routeRepository.save(route);
        log.info("创建线路成功 - 线路ID: {}, 类型: {}, 名称: {}", 
                saved.getRouteId(), saved.getRouteType(), saved.getRouteName());
        return saved;
    }

    private void validateRouteType(Route route) {
        if (route.getRouteType() != null && !route.getRouteType().isEmpty()) {
            if (!routeTypeConfig.isTypeEnabled(route.getRouteType())) {
                throw new BusinessException(400, "不支持的线路类型: " + route.getRouteType());
            }
        }
    }

    private void applyTypeDefaults(Route route) {
        if (route.getRouteType() == null || route.getRouteType().isEmpty()) {
            RouteTypeConfig.RouteTypeDefinition defaultType = routeTypeConfig.getDefaultType();
            if (defaultType != null) {
                route.setRouteType(defaultType.getCode());
                log.debug("使用默认线路类型: {}", defaultType.getCode());
            }
        }

        if (route.getRouteType() != null) {
            routeTypeConfig.getTypeByCode(route.getRouteType()).ifPresent(typeDef -> {
                if (route.getRouteDuration() == null) {
                    route.setRouteDuration(typeDef.getDefaultDuration());
                }
                if (route.getRoutePrice() != null && typeDef.getPriceFactor() != 1.0) {
                    BigDecimal factor = BigDecimal.valueOf(typeDef.getPriceFactor());
                    route.setRoutePrice(route.getRoutePrice().multiply(factor));
                }
            });
        }
    }

    public List<RouteTypeConfig.RouteTypeDefinition> getAllRouteTypes() {
        return routeTypeConfig.getAllEnabledTypes();
    }

    public List<String> getAllRouteTypeCodes() {
        return routeTypeConfig.getAllEnabledTypeCodes();
    }

    public Optional<RouteTypeConfig.RouteTypeDefinition> getRouteTypeDefinition(String typeCode) {
        return routeTypeConfig.getTypeByCode(typeCode);
    }

    public boolean isRouteTypeEnabled(String typeCode) {
        return routeTypeConfig.isTypeEnabled(typeCode);
    }

    public Map<String, Object> getRouteTypeSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<RouteTypeConfig.RouteTypeDefinition> types = routeTypeConfig.getAllEnabledTypes();
        
        summary.put("totalTypes", types.size());
        summary.put("types", types.stream().map(t -> {
            Map<String, Object> typeMap = new LinkedHashMap<>();
            typeMap.put("code", t.getCode());
            typeMap.put("name", t.getName());
            typeMap.put("description", t.getDescription());
            typeMap.put("defaultDuration", t.getDefaultDuration());
            typeMap.put("priceFactor", t.getPriceFactor());
            return typeMap;
        }).toList());
        
        return summary;
    }

    public List<Route> getAllRoutes() {
        return routeRepository.findAll();
    }

    public Optional<Route> getRouteById(String routeId) {
        return routeRepository.findById(routeId);
    }

    public RouteSearchResponse searchRoutes(RouteSearchRequest request) {
        List<Route> routes;

        if (request.getRouteType() != null && !request.getRouteType().isEmpty() &&
            request.getRouteStatus() != null && !request.getRouteStatus().isEmpty()) {
            routes = routeRepository.findByRouteTypeAndRouteStatus(request.getRouteType(), request.getRouteStatus());
        } else if (request.getRouteType() != null && !request.getRouteType().isEmpty()) {
            if (!routeTypeConfig.isTypeEnabled(request.getRouteType())) {
                throw new BusinessException(400, "不支持的线路类型: " + request.getRouteType());
            }
            routes = routeRepository.findByRouteType(request.getRouteType());
        } else if (request.getRouteStatus() != null && !request.getRouteStatus().isEmpty()) {
            routes = routeRepository.findByRouteStatus(request.getRouteStatus());
        } else {
            routes = routeRepository.findAll();
        }

        List<RouteSearchItem> items = routes.stream()
                .map(route -> RouteSearchItem.builder()
                        .routeName(route.getRouteName())
                        .available(route.getRouteAvailable())
                        .build())
                .collect(Collectors.toList());

        return RouteSearchResponse.builder().routes(items).build();
    }

    @Transactional
    public Route updateRoute(String routeId, Route route) {
        Route existing = routeRepository.findById(routeId)
                .orElseThrow(() -> new BusinessException("线路不存在"));

        if (route.getRouteName() != null) {
            existing.setRouteName(route.getRouteName());
        }
        if (route.getRouteType() != null) {
            validateRouteType(route);
            existing.setRouteType(route.getRouteType());
        }
        if (route.getRouteDuration() != null) {
            existing.setRouteDuration(route.getRouteDuration());
        }
        if (route.getRoutePrice() != null) {
            existing.setRoutePrice(route.getRoutePrice());
        }
        if (route.getRouteQuota() != null) {
            existing.setRouteQuota(route.getRouteQuota());
        }
        if (route.getRouteAvailable() != null) {
            existing.setRouteAvailable(route.getRouteAvailable());
        }
        if (route.getRouteStatus() != null) {
            existing.setRouteStatus(route.getRouteStatus());
        }

        return routeRepository.save(existing);
    }

    @Transactional
    public void decreaseQuota(String routeId, int count) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new BusinessException("线路不存在"));

        if (route.getRouteAvailable() < count) {
            throw new BusinessException("线路名额不足");
        }

        int newAvailable = route.getRouteAvailable() - count;
        route.setRouteAvailable(newAvailable);

        if (newAvailable == 0) {
            route.setRouteStatus("full");
        }

        routeRepository.save(route);
    }

    public void deleteRoute(String routeId) {
        routeRepository.deleteById(routeId);
    }

    public Route addRoute(Route route) {
        return createRoute(route);
    }

    @Transactional
    public Route closeRoute(String routeId, String reason) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new BusinessException(404, "线路不存在"));
        
        String newStatus = "名额已满".equals(reason) ? "full" : "closed";
        route.setRouteStatus(newStatus);
        Route saved = routeRepository.save(route);
        
        analyticsService.updateRouteStatistics(saved);
        historyService.recordHistory("route", routeId, "close", reason);
        
        log.info("关闭线路 - 线路ID: {}, 原因: {}, 状态: {}", routeId, reason, newStatus);
        return saved;
    }

    @Transactional
    public Route openRoute(String routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new BusinessException(404, "线路不存在"));
        
        route.setRouteStatus("available");
        Route saved = routeRepository.save(route);
        
        analyticsService.updateRouteStatistics(saved);
        historyService.recordHistory("route", routeId, "open", "线路重新开放");
        
        log.info("开放线路 - 线路ID: {}", routeId);
        return saved;
    }

    @Transactional
    public boolean decreaseQuota(String routeId, int count) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new BusinessException(404, "线路不存在"));

        if ("closed".equals(route.getRouteStatus())) {
            throw new BusinessException(400, "线路不可用");
        }

        if (route.getRouteAvailable() < count) {
            throw new BusinessException(400, "名额不足");
        }

        int newAvailable = route.getRouteAvailable() - count;
        route.setRouteAvailable(newAvailable);

        if (newAvailable == 0) {
            route.setRouteStatus("full");
        }

        routeRepository.save(route);
        return true;
    }

    @Transactional
    public boolean restoreQuota(String routeId, int count) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new BusinessException(404, "线路不存在"));

        if ("closed".equals(route.getRouteStatus())) {
            throw new BusinessException(400, "线路不可用");
        }

        int newAvailable = route.getRouteAvailable() + count;
        if (newAvailable > route.getRouteQuota()) {
            throw new BusinessException(400, "名额超限");
        }

        route.setRouteAvailable(newAvailable);

        if ("full".equals(route.getRouteStatus()) && newAvailable > 0) {
            route.setRouteStatus("available");
        }

        routeRepository.save(route);
        return true;
    }

    public List<Route> getRoutesByType(String routeType) {
        if (!routeTypeConfig.isTypeEnabled(routeType)) {
            log.warn("查询的线路类型未启用: {}", routeType);
        }
        return routeRepository.findByRouteType(routeType);
    }

    public List<Route> queryAvailableRoutes() {
        return routeRepository.findByRouteStatus("available");
    }

    public List<Route> getRoutesByEnabledTypes() {
        List<String> enabledTypes = routeTypeConfig.getAllEnabledTypeCodes();
        if (enabledTypes.isEmpty()) {
            return Collections.emptyList();
        }
        return enabledTypes.stream()
                .flatMap(type -> routeRepository.findByRouteType(type).stream())
                .toList();
    }

    public Map<String, Integer> getRouteCountByType() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<RouteTypeConfig.RouteTypeDefinition> types = routeTypeConfig.getAllEnabledTypes();
        
        for (RouteTypeConfig.RouteTypeDefinition type : types) {
            int count = routeRepository.findByRouteType(type.getCode()).size();
            counts.put(type.getCode() + " - " + type.getName(), count);
        }
        
        return counts;
    }
}
