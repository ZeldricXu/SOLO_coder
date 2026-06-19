package com.designsystem.service;

import com.designsystem.common.enums.ExportFormat;
import com.designsystem.common.util.TokenInheritanceUtil;
import com.designsystem.entity.DesignToken;
import com.designsystem.mapper.DesignTokenMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class TokenCacheService {

    private static final Logger log = LoggerFactory.getLogger(TokenCacheService.class);

    private static final String CACHE_PREFIX = "ds:token:";
    private static final String CACHE_KEY_RESOLVED_VALUES = CACHE_PREFIX + "resolved:values";
    private static final String CACHE_KEY_INHERITANCE_CHAIN = CACHE_PREFIX + "inheritance:chain:";
    private static final String CACHE_KEY_AFFECTED_TOKENS = CACHE_PREFIX + "affected:";
    private static final String CACHE_KEY_EXPORT_FORMAT = CACHE_PREFIX + "export:%s:%s:%s";
    private static final String CACHE_KEY_ALL_TOKENS_SORTED = CACHE_PREFIX + "all:sorted";
    private static final String CACHE_KEY_TOKEN_MAP = CACHE_PREFIX + "map";

    private static final long CACHE_TTL_HOURS = 24;

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final DesignTokenMapper tokenMapper;
    private final DesignTokenService tokenService;
    private TokenInheritanceUtil inheritanceUtil;

    public TokenCacheService(RedisTemplate<String, Object> redisTemplate,
                             StringRedisTemplate stringRedisTemplate,
                             DesignTokenMapper tokenMapper,
                             DesignTokenService tokenService) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.tokenMapper = tokenMapper;
        this.tokenService = tokenService;
    }

    @PostConstruct
    public void init() {
        this.inheritanceUtil = new TokenInheritanceUtil(tokenMapper);
    }

    @SuppressWarnings("unchecked")
    public String getResolvedTokenValue(String tokenName) {
        Map<String, String> resolvedMap = (Map<String, String>) redisTemplate.opsForValue()
                .get(CACHE_KEY_RESOLVED_VALUES);

        if (resolvedMap == null) {
            resolvedMap = buildAndCacheResolvedValues();
        }

        return resolvedMap.get(tokenName);
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getAllResolvedValues() {
        Map<String, String> resolvedMap = (Map<String, String>) redisTemplate.opsForValue()
                .get(CACHE_KEY_RESOLVED_VALUES);

        if (resolvedMap == null) {
            resolvedMap = buildAndCacheResolvedValues();
        }

        return resolvedMap;
    }

    @SuppressWarnings("unchecked")
    public Set<String> getInheritanceChain(String tokenName) {
        String cacheKey = CACHE_KEY_INHERITANCE_CHAIN + tokenName;
        Set<String> chain = (Set<String>) redisTemplate.opsForValue().get(cacheKey);

        if (chain == null) {
            chain = inheritanceUtil.getInheritanceChain(tokenName);
            redisTemplate.opsForValue().set(cacheKey, chain, CACHE_TTL_HOURS, TimeUnit.HOURS);
        }

        return chain;
    }

    @SuppressWarnings("unchecked")
    public Set<String> getAffectedTokens(String modifiedTokenName) {
        String cacheKey = CACHE_KEY_AFFECTED_TOKENS + modifiedTokenName;
        Set<String> affected = (Set<String>) redisTemplate.opsForValue().get(cacheKey);

        if (affected == null) {
            affected = inheritanceUtil.getAffectedTokens(modifiedTokenName);
            redisTemplate.opsForValue().set(cacheKey, affected, CACHE_TTL_HOURS, TimeUnit.HOURS);
        }

        return affected;
    }

    public String getExportFormat(ExportFormat format, String tokenType, String category) {
        String cacheKey = String.format(CACHE_KEY_EXPORT_FORMAT, format, tokenType != null ? tokenType : "all",
                category != null ? category : "all");

        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for export format: {} {} {}", format, tokenType, category);
            return cached;
        }

        log.debug("Cache miss for export format, generating...");
        String generated = tokenService.exportTokens(format, tokenType, category);
        stringRedisTemplate.opsForValue().set(cacheKey, generated, CACHE_TTL_HOURS, TimeUnit.HOURS);
        return generated;
    }

    @SuppressWarnings("unchecked")
    public List<String> getTopologicallySortedTokens() {
        List<String> sorted = (List<String>) redisTemplate.opsForValue().get(CACHE_KEY_ALL_TOKENS_SORTED);

        if (sorted == null) {
            sorted = topologicalSortTokens();
            redisTemplate.opsForValue().set(CACHE_KEY_ALL_TOKENS_SORTED, sorted, CACHE_TTL_HOURS, TimeUnit.HOURS);
        }

        return sorted;
    }

    @SuppressWarnings("unchecked")
    public Map<String, DesignToken> getTokenMap() {
        Map<String, DesignToken> tokenMap = (Map<String, DesignToken>) redisTemplate.opsForValue()
                .get(CACHE_KEY_TOKEN_MAP);

        if (tokenMap == null) {
            List<DesignToken> allTokens = tokenMapper.selectList(null);
            tokenMap = allTokens.stream()
                    .collect(Collectors.toMap(DesignToken::getTokenName, t -> t));
            redisTemplate.opsForValue().set(CACHE_KEY_TOKEN_MAP, tokenMap, CACHE_TTL_HOURS, TimeUnit.HOURS);
        }

        return tokenMap;
    }

    private Map<String, String> buildAndCacheResolvedValues() {
        log.info("Building resolved token values cache...");
        Map<String, String> resolvedMap = inheritanceUtil.resolveAllTokenValues();
        redisTemplate.opsForValue().set(CACHE_KEY_RESOLVED_VALUES, resolvedMap, CACHE_TTL_HOURS, TimeUnit.HOURS);
        log.info("Resolved token values cache built with {} entries", resolvedMap.size());
        return resolvedMap;
    }

    private List<String> topologicalSortTokens() {
        log.info("Performing topological sort on tokens...");

        List<DesignToken> allTokens = tokenMapper.selectList(null);
        Map<String, List<String>> adjacencyList = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (DesignToken token : allTokens) {
            adjacencyList.putIfAbsent(token.getTokenName(), new ArrayList<>());
            inDegree.putIfAbsent(token.getTokenName(), 0);

            if (token.getInheritsFrom() != null && !token.getInheritsFrom().isEmpty()) {
                String parent = token.getInheritsFrom();
                adjacencyList.putIfAbsent(parent, new ArrayList<>());
                adjacencyList.get(parent).add(token.getTokenName());
                inDegree.put(token.getTokenName(), inDegree.getOrDefault(token.getTokenName(), 0) + 1);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);

            List<String> children = adjacencyList.getOrDefault(current, Collections.emptyList());
            for (String child : children) {
                inDegree.put(child, inDegree.get(child) - 1);
                if (inDegree.get(child) == 0) {
                    queue.add(child);
                }
            }
        }

        if (sorted.size() != allTokens.size()) {
            log.warn("Topological sort detected cycle! Sorted: {}, Total: {}", sorted.size(), allTokens.size());
            for (DesignToken token : allTokens) {
                if (!sorted.contains(token.getTokenName())) {
                    sorted.add(token.getTokenName());
                }
            }
        }

        log.info("Topological sort completed, {} tokens sorted", sorted.size());
        return sorted;
    }

    public void invalidateAllCaches() {
        log.info("Invalidating all token caches...");

        Set<String> keys = new HashSet<>();
        keys.add(CACHE_KEY_RESOLVED_VALUES);
        keys.add(CACHE_KEY_ALL_TOKENS_SORTED);
        keys.add(CACHE_KEY_TOKEN_MAP);

        Set<String> allTokenNames = getTokenMap().keySet();
        for (String tokenName : allTokenNames) {
            keys.add(CACHE_KEY_INHERITANCE_CHAIN + tokenName);
            keys.add(CACHE_KEY_AFFECTED_TOKENS + tokenName);
        }

        for (ExportFormat format : ExportFormat.values()) {
            keys.add(String.format(CACHE_KEY_EXPORT_FORMAT, format, "all", "all"));
        }

        redisTemplate.delete(keys);
        inheritanceUtil.clearCache();

        log.info("All token caches invalidated, {} keys cleared", keys.size());
    }

    public void rebuildAllCaches() {
        log.info("Rebuilding all token caches...");
        invalidateAllCaches();

        buildAndCacheResolvedValues();
        topologicalSortTokens();
        getTokenMap();

        for (ExportFormat format : ExportFormat.values()) {
            try {
                getExportFormat(format, null, null);
                log.info("Pre-generated export format: {}", format);
            } catch (Exception e) {
                log.warn("Failed to pre-generate export format {}: {}", format, e.getMessage());
            }
        }

        log.info("All token caches rebuilt successfully");
    }

    public void handleTokenChange(String modifiedTokenName) {
        log.info("Handling token change for: {}", modifiedTokenName);

        Set<String> affectedTokens = getAffectedTokens(modifiedTokenName);
        affectedTokens.add(modifiedTokenName);

        Set<String> keysToInvalidate = new HashSet<>();
        keysToInvalidate.add(CACHE_KEY_RESOLVED_VALUES);
        keysToInvalidate.add(CACHE_KEY_ALL_TOKENS_SORTED);
        keysToInvalidate.add(CACHE_KEY_TOKEN_MAP);

        for (String tokenName : affectedTokens) {
            keysToInvalidate.add(CACHE_KEY_INHERITANCE_CHAIN + tokenName);
            keysToInvalidate.add(CACHE_KEY_AFFECTED_TOKENS + tokenName);
        }

        for (ExportFormat format : ExportFormat.values()) {
            keysToInvalidate.add(String.format(CACHE_KEY_EXPORT_FORMAT, format, "all", "all"));
        }

        redisTemplate.delete(keysToInvalidate);
        inheritanceUtil.clearCache();

        buildAndCacheResolvedValues();
        topologicalSortTokens();
        getTokenMap();

        for (ExportFormat format : ExportFormat.values()) {
            try {
                getExportFormat(format, null, null);
            } catch (Exception e) {
                log.warn("Failed to regenerate export format {}: {}", format, e.getMessage());
            }
        }

        log.info("Token change handled, {} tokens affected, caches rebuilt", affectedTokens.size());
    }
}
