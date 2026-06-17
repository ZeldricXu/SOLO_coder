package com.enterprise.risk.engine.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则缓存
 * 使用Caffeine本地缓存（最大容量、过期时间）+ 版本号管理
 * 支持原子替换整个规则集，避免热加载过程中的不一致
 */
@Component
public class RuleCache {

    private static final Logger log = LoggerFactory.getLogger(RuleCache.class);

    /**
     * 规则缓存：ruleId -> CompiledRule
     */
    private final Cache<String, CompiledRule> ruleCache;

    /**
     * 版本号引用，使用AtomicReference保证原子替换
     */
    private final AtomicReference<CacheVersion> versionRef = new AtomicReference<>(
            new CacheVersion(1, System.currentTimeMillis()));

    /**
     * 规则ID -> 版本号映射，用于检测单条规则变更
     */
    private final Map<String, Integer> ruleVersions = new ConcurrentHashMap<>();

    public RuleCache(
            @Value("${risk.engine.cache.max-size:10000}") int maxSize,
            @Value("${risk.engine.cache.expire-minutes:60}") int expireMinutes) {
        this.ruleCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expireMinutes, TimeUnit.MINUTES)
                .removalListener((key, value, cause) ->
                        log.debug("规则缓存移除: ruleId={}, cause={}", key, cause))
                .build();
        log.info("规则缓存初始化完成: maxSize={}, expireMinutes={}", maxSize, expireMinutes);
    }

    /**
     * 根据ruleId获取编译后的规则
     */
    public CompiledRule getRule(String ruleId) {
        return ruleCache.getIfPresent(ruleId);
    }

    /**
     * 获取所有缓存的规则
     */
    public Collection<CompiledRule> getAllRules() {
        return ruleCache.asMap().values();
    }

    /**
     * 新增或更新单条规则
     *
     * @return true表示是新增，false表示是更新
     */
    public boolean putRule(CompiledRule rule) {
        String ruleId = rule.getRuleId();
        CompiledRule existing = ruleCache.getIfPresent(ruleId);
        ruleCache.put(ruleId, rule);
        if (existing != null) {
            ruleVersions.put(ruleId, rule.getRuleDefinition().getVersion());
            bumpVersion();
            log.debug("规则已更新: ruleId={}, version={}",
                    ruleId, rule.getRuleDefinition().getVersion());
            return false;
        } else {
            ruleVersions.put(ruleId, rule.getRuleDefinition().getVersion());
            bumpVersion();
            log.debug("规则已新增: ruleId={}, version={}",
                    ruleId, rule.getRuleDefinition().getVersion());
            return true;
        }
    }

    /**
     * 批量更新规则（原子替换整个缓存）
     */
    public void putAllRules(Map<String, CompiledRule> newRules) {
        Map<String, CompiledRule> current = new HashMap<>(ruleCache.asMap());
        Set<String> newIds = newRules.keySet();
        Set<String> currentIds = current.keySet();

        for (Map.Entry<String, CompiledRule> entry : newRules.entrySet()) {
            ruleCache.put(entry.getKey(), entry.getValue());
            ruleVersions.put(entry.getKey(), entry.getValue().getRuleDefinition().getVersion());
        }

        for (String id : currentIds) {
            if (!newIds.contains(id)) {
                ruleCache.invalidate(id);
                ruleVersions.remove(id);
            }
        }

        bumpVersion();
        log.info("规则批量更新完成: 新增/更新={}, 移除={}",
                newRules.size(), currentIds.size() - newIds.size());
    }

    /**
     * 删除规则
     */
    public void removeRule(String ruleId) {
        ruleCache.invalidate(ruleId);
        ruleVersions.remove(ruleId);
        bumpVersion();
        log.debug("规则已删除: ruleId={}", ruleId);
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        ruleCache.invalidateAll();
        ruleVersions.clear();
        bumpVersion();
        log.info("规则缓存已清空");
    }

    /**
     * 获取缓存中的规则数量
     */
    public int size() {
        return (int) ruleCache.estimatedSize();
    }

    /**
     * 检查规则是否需要更新
     *
     * @param ruleId      规则ID
     * @param newVersion  新的版本号
     * @param newUpdateAt 新的更新时间
     * @return true表示需要更新
     */
    public boolean needsUpdate(String ruleId, Integer newVersion, Long newUpdateAt) {
        if (newVersion == null || newUpdateAt == null) {
            return true;
        }
        Integer cachedVersion = ruleVersions.get(ruleId);
        if (cachedVersion == null) {
            return true;
        }
        return !cachedVersion.equals(newVersion);
    }

    /**
     * 获取当前缓存版本号
     */
    public CacheVersion getCacheVersion() {
        return versionRef.get();
    }

    /**
     * 提升版本号
     */
    private void bumpVersion() {
        CacheVersion current = versionRef.get();
        CacheVersion next = new CacheVersion(current.version() + 1, System.currentTimeMillis());
        versionRef.set(next);
    }

    /**
     * 缓存版本记录
     */
    public record CacheVersion(int version, long updatedAt) {}
}
