package com.solo.config.module.data;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.solo.config.entity.Resource;
import com.solo.config.mapper.ResourceMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataAccessService {

    private final ResourceMapper resourceMapper;
    private final DataSource dataSource;
    private final Cache<String, Object> queryCache;

    public Mono<Resource> createResource(Resource resource) {
        return Mono.fromCallable(() -> {
            resourceMapper.insert(resource);
            queryCache.invalidateAll();
            log.info("Resource created: {}", resource.getResourceId());
            return resource;
        });
    }

    public Mono<Resource> getResource(String resourceId) {
        String cacheKey = "resource:" + resourceId;
        Object cached = queryCache.getIfPresent(cacheKey);
        if (cached instanceof Resource resource) {
            return Mono.just(resource);
        }

        return Mono.fromCallable(() -> {
            Resource resource = resourceMapper.selectOne(
                    new QueryWrapper<Resource>().eq("resource_id", resourceId)
            );
            if (resource != null) {
                queryCache.put(cacheKey, resource);
            }
            return resource;
        });
    }

    public Flux<Resource> listResources(String type, String status, int page, int size) {
        String cacheKey = "resources:" + type + ":" + status + ":" + page + ":" + size;
        Object cached = queryCache.getIfPresent(cacheKey);
        if (cached instanceof List) {
            return Flux.fromIterable((List<Resource>) cached);
        }

        return Mono.fromCallable(() -> {
            IPage<Resource> pageResult = resourceMapper.selectPage(
                    new Page<>(page, size),
                    new QueryWrapper<Resource>()
                            .eq(type != null, "type", type)
                            .eq(status != null, "status", status)
                            .orderByDesc("created_at")
            );
            queryCache.put(cacheKey, pageResult.getRecords());
            return pageResult.getRecords();
        }).flatMapMany(Flux::fromIterable);
    }

    public Mono<Resource> updateResource(String resourceId, Resource resource) {
        return Mono.fromCallable(() -> {
            Resource existing = resourceMapper.selectOne(
                    new QueryWrapper<Resource>().eq("resource_id", resourceId)
            );
            if (existing == null) {
                return null;
            }
            if (resource.getType() != null) {
                existing.setType(resource.getType());
            }
            if (resource.getStatus() != null) {
                existing.setStatus(resource.getStatus());
            }
            if (resource.getAttributes() != null) {
                existing.setAttributes(resource.getAttributes());
            }
            if (resource.getConfig() != null) {
                existing.setConfig(resource.getConfig());
            }
            if (resource.getLabels() != null) {
                existing.setLabels(resource.getLabels());
            }
            resourceMapper.updateById(existing);
            queryCache.invalidateAll();
            log.info("Resource updated: {}", resourceId);
            return existing;
        });
    }

    public Mono<Void> deleteResource(String resourceId) {
        return Mono.fromRunnable(() -> {
            Resource resource = resourceMapper.selectOne(
                    new QueryWrapper<Resource>().eq("resource_id", resourceId)
            );
            if (resource != null) {
                resourceMapper.deleteById(resource.getId());
                queryCache.invalidateAll();
                log.info("Resource deleted: {}", resourceId);
            }
        });
    }

    public Mono<Map<String, Object>> getDataSourceStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();
            if (dataSource instanceof HikariDataSource hikariDataSource) {
                HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();
                stats.put("totalConnections", poolMXBean.getTotalConnections());
                stats.put("activeConnections", poolMXBean.getActiveConnections());
                stats.put("idleConnections", poolMXBean.getIdleConnections());
                stats.put("threadsAwaitingConnection", poolMXBean.getThreadsAwaitingConnection());
            }
            return stats;
        });
    }
}
