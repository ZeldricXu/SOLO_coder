package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.entity.SysApiKey;
import com.metricplatform.entity.SysGatewayRoute;
import com.metricplatform.mapper.SysApiKeyMapper;
import com.metricplatform.mapper.SysGatewayRouteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService extends ServiceImpl<SysGatewayRouteMapper, SysGatewayRoute> {

    private final SysApiKeyMapper apiKeyMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(rollbackFor = Exception.class)
    public SysGatewayRoute createRoute(String path, String targetUrl, boolean authRequired,
                                       boolean rateLimitEnabled, Integer rateLimitCapacity, Integer rateLimitRefill) {
        SysGatewayRoute route = new SysGatewayRoute();
        route.setRouteId("route_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        route.setPath(path);
        route.setTargetUrl(targetUrl);
        route.setAuthRequired(authRequired);
        route.setRateLimitEnabled(rateLimitEnabled);
        route.setRateLimitCapacity(rateLimitCapacity);
        route.setRateLimitRefill(rateLimitRefill);
        route.setEnabled(true);

        this.save(route);
        log.info("已创建网关路由: {} -> {}", path, targetUrl);
        return route;
    }

    @Transactional(rollbackFor = Exception.class)
    public SysApiKey createApiKey(String name, List<String> permissions, Integer rateLimitCapacity, LocalDateTime expireAt) {
        String apiKey = generateRandomString(32);
        String secretKey = generateRandomString(64);

        SysApiKey apiKeyEntity = new SysApiKey();
        apiKeyEntity.setKeyId("key_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        apiKeyEntity.setApiKey(apiKey);
        apiKeyEntity.setSecretKey(secretKey);
        apiKeyEntity.setName(name);
        apiKeyEntity.setPermissions(permissions);
        apiKeyEntity.setRateLimitCapacity(rateLimitCapacity != null ? rateLimitCapacity : 1000);
        apiKeyEntity.setStatus("active");
        apiKeyEntity.setExpireAt(expireAt);

        apiKeyMapper.insert(apiKeyEntity);
        log.info("已创建API Key: {} (ID: {})", name, apiKeyEntity.getKeyId());
        return apiKeyEntity;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean revokeApiKey(String keyId) {
        SysApiKey apiKey = apiKeyMapper.selectById(keyId);
        if (apiKey == null) {
            return false;
        }
        apiKey.setStatus("revoked");
        apiKeyMapper.updateById(apiKey);
        log.info("已吊销API Key: {}", apiKey.getName());
        return true;
    }

    public SysApiKey validateApiKey(String apiKey) {
        return apiKeyMapper.selectOne(new LambdaQueryWrapper<SysApiKey>()
                .eq(SysApiKey::getApiKey, apiKey)
                .eq(SysApiKey::getStatus, "active")
                .and(w -> w.isNull(SysApiKey::getExpireAt)
                        .or()
                        .gt(SysApiKey::getExpireAt, LocalDateTime.now())));
    }

    public List<SysGatewayRoute> getAllRoutes() {
        return this.list(new LambdaQueryWrapper<SysGatewayRoute>().orderByAsc(SysGatewayRoute::getPath));
    }

    public List<SysApiKey> getAllApiKeys() {
        return apiKeyMapper.selectList(new LambdaQueryWrapper<SysApiKey>()
                .orderByDesc(SysApiKey::getCreatedAt));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRoute(String routeId) {
        return this.removeById(routeId);
    }

    private String generateRandomString(int length) {
        byte[] randomBytes = new byte[length];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes).substring(0, length);
    }

    public SysGatewayRoute getRouteById(String routeId) {
        return this.getById(routeId);
    }

    public SysApiKey getApiKeyById(String keyId) {
        return apiKeyMapper.selectById(keyId);
    }

    @Transactional(rollbackFor = Exception.class)
    public SysGatewayRoute updateRoute(String routeId, Map<String, Object> updates) {
        SysGatewayRoute route = this.getById(routeId);
        if (route == null) {
            throw new IllegalArgumentException("路由不存在: " + routeId);
        }

        if (updates.containsKey("enabled")) {
            route.setEnabled((Boolean) updates.get("enabled"));
        }
        if (updates.containsKey("authRequired")) {
            route.setAuthRequired((Boolean) updates.get("authRequired"));
        }
        if (updates.containsKey("rateLimitEnabled")) {
            route.setRateLimitEnabled((Boolean) updates.get("rateLimitEnabled"));
        }
        if (updates.containsKey("targetUrl")) {
            route.setTargetUrl((String) updates.get("targetUrl"));
        }

        this.updateById(route);
        return route;
    }
}
