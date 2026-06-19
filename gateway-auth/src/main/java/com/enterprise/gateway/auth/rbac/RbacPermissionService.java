package com.enterprise.gateway.auth.rbac;

import com.enterprise.gateway.admin.mapper.ApiPermissionMapper;
import com.enterprise.gateway.admin.mapper.RolePermissionMapper;
import com.enterprise.gateway.common.model.ApiPermission;
import com.enterprise.gateway.common.model.RolePermission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RbacPermissionService {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String RBAC_ROLE_KEY_PREFIX = "rbac:role:";

    public Mono<Boolean> hasPermission(List<String> roles, String path, String method) {
        if (roles == null || roles.isEmpty()) {
            return Mono.just(false);
        }

        return Flux.fromIterable(roles)
                .flatMap(role -> getRolePermissions(role)
                        .map(permissions -> matchPermission(permissions, path, method)))
                .any(Boolean::booleanValue);
    }

    public Mono<Void> loadPermissions() {
        return refreshPermissions();
    }

    public Mono<Void> refreshPermissions() {
        List<RolePermission> rolePermissions = queryAllRolePermissions();
        List<ApiPermission> apiPermissions = queryAllApiPermissions();

        Map<String, List<ApiPermission>> roleToPermissionsMap = new ConcurrentHashMap<>();

        for (RolePermission rp : rolePermissions) {
            apiPermissions.stream()
                    .filter(ap -> ap.getPermissionCode().equals(rp.getPermissionCode()))
                    .forEach(ap -> {
                        roleToPermissionsMap.computeIfAbsent(rp.getRoleCode(), k -> new ArrayList<>())
                                .add(ap);
                    });
        }

        return Flux.fromIterable(roleToPermissionsMap.entrySet())
                .flatMap(entry -> {
                    String key = RBAC_ROLE_KEY_PREFIX + entry.getKey();
                    try {
                        String value = objectMapper.writeValueAsString(entry.getValue());
                        return redisTemplate.opsForValue().set(key, value);
                    } catch (Exception e) {
                        log.error("Failed to serialize permissions for role: {}", entry.getKey(), e);
                        return Mono.error(e);
                    }
                })
                .then()
                .doOnSuccess(v -> log.info("Successfully refreshed RBAC permissions in Redis"))
                .doOnError(e -> log.error("Failed to refresh RBAC permissions", e));
    }

    private Mono<List<ApiPermission>> getRolePermissions(String roleCode) {
        String key = RBAC_ROLE_KEY_PREFIX + roleCode;
        return redisTemplate.opsForValue().get(key)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, new TypeReference<List<ApiPermission>>() {});
                    } catch (Exception e) {
                        log.error("Failed to deserialize permissions for role: {}", roleCode, e);
                        return Collections.<ApiPermission>emptyList();
                    }
                })
                .defaultIfEmpty(Collections.emptyList());
    }

    private boolean matchPermission(List<ApiPermission> permissions, String path, String method) {
        for (ApiPermission permission : permissions) {
            if (matchMethod(permission.getMethod(), method) && matchPath(permission.getPathPattern(), path)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchMethod(String permissionMethod, String requestMethod) {
        if (permissionMethod == null || "*".equals(permissionMethod)) {
            return true;
        }
        return permissionMethod.equalsIgnoreCase(requestMethod);
    }

    private boolean matchPath(String pathPattern, String requestPath) {
        if (pathPattern == null) {
            return false;
        }

        String regexPattern = pathPattern
                .replace("**", ".*")
                .replace("*", "[^/]*")
                .replace("{id}", "[^/]+");

        return Pattern.matches(regexPattern, requestPath);
    }

    public void refreshAll() {
        try {
            refreshPermissions().block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.error("Failed to refresh RBAC permissions", e);
        }
    }

    protected List<RolePermission> queryAllRolePermissions() {
        return rolePermissionMapper.selectList(null);
    }

    protected List<ApiPermission> queryAllApiPermissions() {
        return apiPermissionMapper.selectList(null);
    }
}
