package com.enterprise.gateway.admin.service;

import com.alibaba.nacos.api.config.ConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.gateway.admin.mapper.RouteMapper;
import com.enterprise.gateway.common.model.RouteDefinition;
import com.enterprise.gateway.common.util.JacksonUtil;
import com.enterprise.gateway.routing.DynamicRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService {

    private final RouteMapper routeMapper;
    private final DynamicRouteService dynamicRouteService;
    private final ConfigService configService;

    private static final String DATA_ID = "gateway-routes";
    private static final String GROUP_ID = "DEFAULT_GROUP";

    public Page<RouteDefinition> listRoutes(int page, int size) {
        Page<RouteDefinition> pageParam = new Page<>(page, size);
        return routeMapper.selectPage(pageParam, new LambdaQueryWrapper<RouteDefinition>()
                .orderByDesc(RouteDefinition::getCreatedAt));
    }

    public RouteDefinition getRouteById(Long id) {
        return routeMapper.selectById(id);
    }

    public RouteDefinition createRoute(RouteDefinition entity) {
        validateRoute(entity);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        routeMapper.insert(entity);
        publishRoutesToNacos();
        return entity;
    }

    public RouteDefinition updateRoute(RouteDefinition entity) {
        validateRoute(entity);
        entity.setUpdatedAt(LocalDateTime.now());
        routeMapper.updateById(entity);
        RouteDefinition updated = routeMapper.selectById(entity.getId());
        if (updated.getStatus() == 1) {
            dynamicRouteService.updateRoute(dynamicRouteService.convertToGatewayRoute(updated));
        } else {
            dynamicRouteService.deleteRoute(updated.getRouteId());
        }
        publishRoutesToNacos();
        return updated;
    }

    public void deleteRoute(Long id) {
        RouteDefinition entity = routeMapper.selectById(id);
        if (entity != null) {
            entity.setStatus(0);
            entity.setUpdatedAt(LocalDateTime.now());
            routeMapper.updateById(entity);
            dynamicRouteService.deleteRoute(entity.getRouteId());
            publishRoutesToNacos();
        }
    }

    public void refreshAll() {
        List<RouteDefinition> entities = routeMapper.selectList(new LambdaQueryWrapper<RouteDefinition>()
                .eq(RouteDefinition::getStatus, 1));
        List<org.springframework.cloud.gateway.route.RouteDefinition> definitions = entities.stream()
                .map(dynamicRouteService::convertToGatewayRoute)
                .toList();
        dynamicRouteService.refreshRoutes(definitions);
        publishRoutesToNacos();
    }

    public RouteDefinition enableRoute(Long id, Integer status) {
        RouteDefinition entity = routeMapper.selectById(id);
        if (entity != null) {
            entity.setStatus(status);
            entity.setUpdatedAt(LocalDateTime.now());
            routeMapper.updateById(entity);
            if (status == 1) {
                dynamicRouteService.addRoute(dynamicRouteService.convertToGatewayRoute(entity));
            } else {
                dynamicRouteService.deleteRoute(entity.getRouteId());
            }
            publishRoutesToNacos();
        }
        return entity;
    }

    private void validateRoute(RouteDefinition entity) {
        if (entity.getRouteId() == null || entity.getRouteId().isEmpty()) {
            throw new IllegalArgumentException("RouteId cannot be empty");
        }
        if (entity.getUri() == null || entity.getUri().isEmpty()) {
            throw new IllegalArgumentException("Uri cannot be empty");
        }
    }

    private void publishRoutesToNacos() {
        try {
            List<RouteDefinition> entities = routeMapper.selectList(new LambdaQueryWrapper<RouteDefinition>()
                    .eq(RouteDefinition::getStatus, 1));
            String config = JacksonUtil.toJson(entities);
            configService.publishConfig(DATA_ID, GROUP_ID, config);
            log.info("Published {} routes to Nacos", entities.size());
        } catch (Exception e) {
            log.error("Failed to publish routes to Nacos", e);
        }
    }
}
