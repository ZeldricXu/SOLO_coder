package com.designsystem.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.designsystem.common.PageQuery;
import com.designsystem.common.enums.ExportFormat;
import com.designsystem.common.enums.TokenLevel;
import com.designsystem.common.util.TokenInheritanceUtil;
import com.designsystem.entity.Component;
import com.designsystem.entity.DesignToken;
import com.designsystem.entity.TokenChange;
import com.designsystem.entity.TokenOverride;
import com.designsystem.mapper.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.designsystem.config.RabbitMQConfig.*;

@Service
public class DesignTokenService {

    private static final Logger log = LoggerFactory.getLogger(DesignTokenService.class);

    private static final String CACHE_PREFIX = "design:token:";
    private static final String CACHE_KEY_GRAPH = CACHE_PREFIX + "graph:topology";
    private static final String CACHE_KEY_EXPORT_CSS = CACHE_PREFIX + "export:css";
    private static final String CACHE_KEY_EXPORT_JS = CACHE_PREFIX + "export:js";
    private static final String CACHE_KEY_EXPORT_JSON = CACHE_PREFIX + "export:json";
    private static final String CACHE_KEY_RESOLVED_VALUES = CACHE_PREFIX + "resolved:values";
    private static final String CACHE_KEY_AFFECTED = CACHE_PREFIX + "affected:";
    private static final long CACHE_TTL_HOURS = 24;

    private final DesignTokenMapper tokenMapper;
    private final TokenOverrideMapper overrideMapper;
    private final ComponentTokenUsageMapper usageMapper;
    private final TokenChangeMapper changeMapper;
    private final ComponentMapper componentMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private TokenInheritanceUtil inheritanceUtil;

    public DesignTokenService(DesignTokenMapper tokenMapper, TokenOverrideMapper overrideMapper,
                              ComponentTokenUsageMapper usageMapper, TokenChangeMapper changeMapper,
                              ComponentMapper componentMapper, RabbitTemplate rabbitTemplate,
                              RedisTemplate<String, Object> redisTemplate) {
        this.tokenMapper = tokenMapper;
        this.overrideMapper = overrideMapper;
        this.usageMapper = usageMapper;
        this.changeMapper = changeMapper;
        this.componentMapper = componentMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        this.inheritanceUtil = new TokenInheritanceUtil(tokenMapper);
        try {
            refreshCache();
            log.info("Design token cache initialized successfully");
        } catch (Exception e) {
            log.warn("Failed to initialize token cache on startup: {}", e.getMessage());
        }
    }

    @Async
    public void refreshCacheAsync() {
        refreshCache();
    }

    public void refreshCache() {
        log.debug("Refreshing design token cache...");
        long startTime = System.currentTimeMillis();

        try {
            List<DesignToken> allTokens = tokenMapper.selectList(null);
            if (allTokens.isEmpty()) {
                return;
            }

            Map<String, String> resolvedValues = resolveAllTokenValues(allTokens);
            redisTemplate.opsForValue().set(CACHE_KEY_RESOLVED_VALUES, resolvedValues, CACHE_TTL_HOURS, TimeUnit.HOURS);

            List<String> topology = topologicalSort(allTokens);
            redisTemplate.opsForValue().set(CACHE_KEY_GRAPH, topology, CACHE_TTL_HOURS, TimeUnit.HOURS);

            pregenerateExports(allTokens, resolvedValues);

            for (DesignToken token : allTokens) {
                Set<String> affected = calculateAffectedTokens(token.getTokenName(), allTokens);
                redisTemplate.opsForValue().set(CACHE_KEY_AFFECTED + token.getTokenName(),
                        affected, CACHE_TTL_HOURS, TimeUnit.HOURS);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Token cache refreshed successfully in {}ms for {} tokens", duration, allTokens.size());
        } catch (Exception e) {
            log.error("Failed to refresh token cache", e);
            throw new RuntimeException("Token cache refresh failed", e);
        }
    }

    private Map<String, String> resolveAllTokenValues(List<DesignToken> tokens) {
        Map<String, String> resolved = new HashMap<>();
        Map<String, DesignToken> tokenMap = tokens.stream()
                .collect(Collectors.toMap(DesignToken::getTokenName, t -> t));

        for (DesignToken token : tokens) {
            resolved.put(token.getTokenName(), resolveTokenValueFromMap(token.getTokenName(), tokenMap, new HashSet<>()));
        }

        return resolved;
    }

    private String resolveTokenValueFromMap(String tokenName, Map<String, DesignToken> tokenMap, Set<String> visited) {
        if (visited.contains(tokenName)) {
            return null;
        }
        visited.add(tokenName);

        DesignToken token = tokenMap.get(tokenName);
        if (token == null) {
            return null;
        }

        String value = token.getBaseValue();
        if (value != null && !value.isEmpty()) {
            return value;
        }

        if (token.getInheritsFrom() != null && !token.getInheritsFrom().isEmpty()) {
            return resolveTokenValueFromMap(token.getInheritsFrom(), tokenMap, visited);
        }

        return value;
    }

    public List<String> topologicalSort(List<DesignToken> tokens) {
        Map<String, List<String>> adjacencyList = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (DesignToken token : tokens) {
            adjacencyList.put(token.getTokenName(), new ArrayList<>());
            inDegree.put(token.getTokenName(), 0);
        }

        for (DesignToken token : tokens) {
            if (token.getInheritsFrom() != null && !token.getInheritsFrom().isEmpty()) {
                String parent = token.getInheritsFrom();
                if (adjacencyList.containsKey(parent)) {
                    adjacencyList.get(parent).add(token.getTokenName());
                    inDegree.merge(token.getTokenName(), 1, Integer::sum);
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);

            for (String child : adjacencyList.getOrDefault(current, Collections.emptyList())) {
                inDegree.merge(child, -1, Integer::sum);
                if (inDegree.get(child) == 0) {
                    queue.offer(child);
                }
            }
        }

        if (result.size() != tokens.size()) {
            log.warn("Topological sort detected cycle: processed {}/{} tokens", result.size(), tokens.size());
        }

        return result;
    }

    private Set<String> calculateAffectedTokens(String tokenName, List<DesignToken> tokens) {
        Set<String> affected = new HashSet<>();
        Map<String, List<String>> children = new HashMap<>();

        for (DesignToken token : tokens) {
            if (token.getInheritsFrom() != null && !token.getInheritsFrom().isEmpty()) {
                children.computeIfAbsent(token.getInheritsFrom(), k -> new ArrayList<>())
                        .add(token.getTokenName());
            }
        }

        Queue<String> queue = new LinkedList<>();
        queue.offer(tokenName);
        affected.add(tokenName);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : children.getOrDefault(current, Collections.emptyList())) {
                if (!affected.contains(child)) {
                    affected.add(child);
                    queue.offer(child);
                }
            }
        }

        affected.remove(tokenName);
        return affected;
    }

    private void pregenerateExports(List<DesignToken> tokens, Map<String, String> resolvedValues) {
        List<DesignToken> enrichedTokens = tokens.stream()
                .peek(this::enrichToken)
                .peek(t -> {
                    String resolved = resolvedValues.get(t.getTokenName());
                    if (resolved != null && (t.getBaseValue() == null || t.getBaseValue().isEmpty())) {
                        t.setBaseValue(resolved);
                    }
                })
                .collect(Collectors.toList());

        redisTemplate.opsForValue().set(CACHE_KEY_EXPORT_CSS,
                exportToCss(enrichedTokens), CACHE_TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.opsForValue().set(CACHE_KEY_EXPORT_JS,
                exportToJs(enrichedTokens), CACHE_TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.opsForValue().set(CACHE_KEY_EXPORT_JSON,
                exportToJson(enrichedTokens), CACHE_TTL_HOURS, TimeUnit.HOURS);
    }

    public IPage<DesignToken> getTokenPage(PageQuery query, String tokenType, String tokenLevel, String category) {
        Page<DesignToken> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<DesignToken> result = tokenMapper.selectTokenPage(page, query.getKeyword(), tokenType, tokenLevel, category);
        result.getRecords().forEach(this::enrichToken);
        result.getRecords().forEach(this::applyCachedValue);
        return result;
    }

    private void applyCachedValue(DesignToken token) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> cachedValues = (Map<String, String>) redisTemplate.opsForValue()
                    .get(CACHE_KEY_RESOLVED_VALUES);
            if (cachedValues != null && cachedValues.containsKey(token.getTokenName())) {
                token.setResolvedValue(cachedValues.get(token.getTokenName()));
            }
        } catch (Exception e) {
            log.debug("Cache not available for token: {}", token.getTokenName());
        }
    }

    public DesignToken getTokenById(Long id) {
        DesignToken token = tokenMapper.selectById(id);
        if (token != null) {
            enrichToken(token);
            applyCachedValue(token);
            if (token.getInheritsFrom() != null) {
                token.setParentToken(tokenMapper.selectByName(token.getInheritsFrom()));
            }
            token.setChildTokens(tokenMapper.selectByParentId(token.getTokenName()));
        }
        return token;
    }

    public DesignToken getTokenByName(String tokenName) {
        DesignToken token = tokenMapper.selectByName(tokenName);
        if (token != null) {
            enrichToken(token);
            applyCachedValue(token);
        }
        return token;
    }

    public List<DesignToken> getTokenTree() {
        List<DesignToken> baseTokens = tokenMapper.selectByLevel(TokenLevel.BASE.getCode());
        baseTokens.forEach(this::buildTokenTree);
        return baseTokens;
    }

    private void buildTokenTree(DesignToken token) {
        enrichToken(token);
        applyCachedValue(token);
        List<DesignToken> children = tokenMapper.selectByParentId(token.getTokenName());
        token.setChildTokens(children);
        children.forEach(this::buildTokenTree);
    }

    @Transactional(rollbackFor = Exception.class)
    public DesignToken createToken(DesignToken token) {
        token.setStatus(1);
        tokenMapper.insert(token);

        refreshCacheAsync();
        rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_TOKEN_CHANGE, token.getId());

        return token;
    }

    @Transactional(rollbackFor = Exception.class)
    public DesignToken updateToken(DesignToken token) {
        DesignToken oldToken = tokenMapper.selectById(token.getId());
        if (oldToken == null) {
            throw new RuntimeException("Token not found");
        }

        if (token.getInheritsFrom() != null && checkCircularReference(token.getTokenName(), token.getInheritsFrom())) {
            throw new IllegalArgumentException("Circular reference detected in token inheritance chain");
        }
        inheritanceUtil.clearCache();

        TokenChange change = new TokenChange();
        change.setTokenId(token.getId());
        change.setChangeType("UPDATE");
        change.setOldValue(oldToken.getBaseValue());
        change.setNewValue(token.getBaseValue());
        change.setOldName(oldToken.getTokenName());
        change.setNewName(token.getTokenName());
        change.setEffectiveDate(LocalDateTime.now());

        String migrationGuide = generateMigrationGuide(oldToken, token);
        change.setMigrationGuide(migrationGuide);

        List<Component> affectedComponents = getAffectedComponents(token.getId());
        change.setAffectedComponents(affectedComponents.stream()
                .map(Component::getName)
                .collect(Collectors.joining(",")));

        changeMapper.insert(change);
        tokenMapper.updateById(token);

        refreshCacheAsync();

        Map<String, Object> changeEvent = new HashMap<>();
        changeEvent.put("tokenId", token.getId());
        changeEvent.put("changeId", change.getId());
        rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_TOKEN_CHANGE, changeEvent);

        return token;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteToken(Long id) {
        DesignToken token = tokenMapper.selectById(id);
        if (token == null) {
            throw new RuntimeException("Token not found");
        }

        token.setStatus(0);
        tokenMapper.updateById(token);

        refreshCacheAsync();

        log.info("Token deleted: {}", token.getTokenName());
    }

    public String exportTokens(ExportFormat format, String tokenType, String tokenLevel) {
        if (tokenType == null && tokenLevel == null) {
            String cached = getCachedExport(format);
            if (cached != null) {
                log.debug("Cache hit for token export: {}", format);
                return cached;
            }
        }

        List<DesignToken> tokens;
        if (tokenLevel != null) {
            tokens = tokenMapper.selectByLevel(tokenLevel);
        } else if (tokenType != null) {
            tokens = tokenMapper.selectByType(tokenType);
        } else {
            tokens = tokenMapper.selectList(null);
        }

        tokens.forEach(this::enrichToken);
        tokens.forEach(this::resolveTokenValue);

        String result = switch (format) {
            case CSS -> exportToCss(tokens);
            case JS -> exportToJs(tokens);
            case JSON -> exportToJson(tokens);
            case SCSS -> exportToScss(tokens);
            case LESS -> exportToLess(tokens);
            case ANDROID -> exportToAndroid(tokens);
            case IOS -> exportToIos(tokens);
        };

        if (tokenType == null && tokenLevel == null) {
            cacheExport(format, result);
        }

        return result;
    }

    private String getCachedExport(ExportFormat format) {
        try {
            String cacheKey = switch (format) {
                case CSS -> CACHE_KEY_EXPORT_CSS;
                case JS -> CACHE_KEY_EXPORT_JS;
                case JSON -> CACHE_KEY_EXPORT_JSON;
                default -> null;
            };
            if (cacheKey == null) {
                return null;
            }
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            return cached != null ? cached.toString() : null;
        } catch (Exception e) {
            log.debug("Cache not available for export: {}", format);
            return null;
        }
    }

    private void cacheExport(ExportFormat format, String content) {
        try {
            String cacheKey = switch (format) {
                case CSS -> CACHE_KEY_EXPORT_CSS;
                case JS -> CACHE_KEY_EXPORT_JS;
                case JSON -> CACHE_KEY_EXPORT_JSON;
                default -> null;
            };
            if (cacheKey != null) {
                redisTemplate.opsForValue().set(cacheKey, content, CACHE_TTL_HOURS, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            log.debug("Failed to cache export: {}", format);
        }
    }

    public List<Component> getAffectedComponents(Long tokenId) {
        DesignToken token = tokenMapper.selectById(tokenId);
        if (token == null) {
            return Collections.emptyList();
        }

        Set<String> affectedTokenNames = getAffectedTokens(token.getTokenName());
        affectedTokenNames.add(token.getTokenName());

        Set<Long> componentIds = new HashSet<>();
        for (String tokenName : affectedTokenNames) {
            DesignToken t = tokenMapper.selectByName(tokenName);
            if (t != null) {
                usageMapper.selectByTokenId(t.getId())
                        .forEach(usage -> componentIds.add(usage.getComponentId()));
            }
        }

        return componentIds.isEmpty() ? new ArrayList<>() : componentMapper.selectBatchIds(componentIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public TokenOverride addOverride(TokenOverride override) {
        overrideMapper.insert(override);
        refreshCacheAsync();
        return override;
    }

    public List<TokenOverride> getOverridesByTokenId(Long tokenId) {
        return overrideMapper.selectByTokenId(tokenId);
    }

    public Map<String, Object> getTokenImpactAnalysis(Long tokenId) {
        DesignToken token = getTokenById(tokenId);
        List<Component> affectedComponents = getAffectedComponents(tokenId);

        Set<String> affectedTokenNames = Collections.emptySet();
        if (token != null) {
            affectedTokenNames = getAffectedTokens(token.getTokenName());
        }

        List<DesignToken> affectedTokens = new ArrayList<>();
        for (String name : affectedTokenNames) {
            DesignToken t = tokenMapper.selectByName(name);
            if (t != null) {
                enrichToken(t);
                applyCachedValue(t);
                affectedTokens.add(t);
            }
        }

        List<TokenChange> changeHistory = changeMapper.selectByTokenId(tokenId);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("affectedComponents", affectedComponents);
        result.put("affectedTokens", affectedTokens);
        result.put("changeHistory", changeHistory);
        return result;
    }

    public boolean checkCircularReference(String tokenName, String inheritsFrom) {
        return inheritanceUtil.hasCircularReference(tokenName, inheritsFrom);
    }

    public List<String> detectAllCircularReferences() {
        return inheritanceUtil.detectAllCircularReferences();
    }

    public String resolveTokenValue(String tokenName) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> cachedValues = (Map<String, String>) redisTemplate.opsForValue()
                    .get(CACHE_KEY_RESOLVED_VALUES);
            if (cachedValues != null && cachedValues.containsKey(tokenName)) {
                return cachedValues.get(tokenName);
            }
        } catch (Exception e) {
            log.debug("Cache miss for token value: {}", tokenName);
        }

        return inheritanceUtil.resolveTokenValue(tokenName);
    }

    @SuppressWarnings("unchecked")
    public Set<String> getAffectedTokens(String modifiedTokenName) {
        try {
            Object cached = redisTemplate.opsForValue().get(CACHE_KEY_AFFECTED + modifiedTokenName);
            if (cached != null) {
                return (Set<String>) cached;
            }
        } catch (Exception e) {
            log.debug("Cache miss for affected tokens: {}", modifiedTokenName);
        }

        return inheritanceUtil.getAffectedTokens(modifiedTokenName);
    }

    public Set<String> getInheritanceChain(String tokenName) {
        return inheritanceUtil.getInheritanceChain(tokenName);
    }

    @SuppressWarnings("unchecked")
    public List<String> getTopologicalOrder() {
        try {
            Object cached = redisTemplate.opsForValue().get(CACHE_KEY_GRAPH);
            if (cached != null) {
                return (List<String>) cached;
            }
        } catch (Exception e) {
            log.debug("Cache miss for topology graph");
        }

        List<DesignToken> tokens = tokenMapper.selectList(null);
        return topologicalSort(tokens);
    }

    private void enrichToken(DesignToken token) {
        token.setOverrides(overrideMapper.selectByTokenId(token.getId()));
        token.setComponentUsages(usageMapper.selectByTokenId(token.getId()));
    }

    private void resolveTokenValue(DesignToken token) {
        if (token.getInheritsFrom() != null && !token.getInheritsFrom().isEmpty()) {
            DesignToken parent = tokenMapper.selectByName(token.getInheritsFrom());
            if (parent != null && (token.getBaseValue() == null || token.getBaseValue().isEmpty())) {
                resolveTokenValue(parent);
                token.setBaseValue(parent.getBaseValue());
            }
        }
    }

    private String generateMigrationGuide(DesignToken oldToken, DesignToken newToken) {
        StringBuilder guide = new StringBuilder();
        guide.append("# 令牌迁移指南\n\n");

        if (!oldToken.getTokenName().equals(newToken.getTokenName())) {
            guide.append("## 令牌重命名\n\n");
            guide.append("- 旧名称: `").append(oldToken.getTokenName()).append("`\n");
            guide.append("- 新名称: `").append(newToken.getTokenName()).append("`\n\n");
            guide.append("### 代码替换示例:\n\n");
            guide.append("```css\n");
            guide.append("/* 旧代码 */\n");
            guide.append("color: var(").append(oldToken.getTokenName()).append(");\n\n");
            guide.append("/* 新代码 */\n");
            guide.append("color: var(").append(newToken.getTokenName()).append(");\n");
            guide.append("```\n\n");
        }

        if (!Objects.equals(oldToken.getBaseValue(), newToken.getBaseValue())) {
            guide.append("## 值变更\n\n");
            guide.append("- 旧值: `").append(oldToken.getBaseValue()).append("`\n");
            guide.append("- 新值: `").append(newToken.getBaseValue()).append("`\n\n");
            guide.append("请检查视觉效果是否符合预期。\n\n");
        }

        if (newToken.getStatus() != null && newToken.getStatus() == 0) {
            guide.append("## 令牌废弃\n\n");
            guide.append("此令牌已被废弃，请使用替代方案。\n");
            if (newToken.getDeprecatedBy() != null) {
                guide.append("替代令牌: `").append(newToken.getDeprecatedBy()).append("`\n");
            }
        }

        return guide.toString();
    }

    private String exportToCss(List<DesignToken> tokens) {
        StringBuilder sb = new StringBuilder(":root {\n");
        for (DesignToken token : tokens) {
            sb.append("  ").append(token.getTokenName()).append(": ").append(token.getBaseValue()).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String exportToJs(List<DesignToken> tokens) {
        StringBuilder sb = new StringBuilder("export const designTokens = {\n");
        for (DesignToken token : tokens) {
            String jsName = token.getTokenName().replace("--", "").replace("-", "_").toUpperCase();
            sb.append("  ").append(jsName).append(": '").append(token.getBaseValue()).append("',\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    private String exportToJson(List<DesignToken> tokens) {
        StringBuilder sb = new StringBuilder("{\n  \"tokens\": [\n");
        for (int i = 0; i < tokens.size(); i++) {
            DesignToken token = tokens.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": \"").append(token.getTokenName()).append("\",\n");
            sb.append("      \"value\": \"").append(token.getBaseValue()).append("\",\n");
            sb.append("      \"type\": \"").append(token.getTokenType()).append("\",\n");
            sb.append("      \"level\": \"").append(token.getTokenLevel()).append("\"\n");
            sb.append("    }").append(i < tokens.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private String exportToScss(List<DesignToken> tokens) {
        StringBuilder sb = new StringBuilder();
        for (DesignToken token : tokens) {
            String scssName = token.getTokenName().replace("--", "$").replace("-", "_");
            sb.append(scssName).append(": ").append(token.getBaseValue()).append(";\n");
        }
        return sb.toString();
    }

    private String exportToLess(List<DesignToken> tokens) {
        StringBuilder sb = new StringBuilder();
        for (DesignToken token : tokens) {
            String lessName = token.getTokenName().replace("--", "@").replace("-", "_");
            sb.append(lessName).append(": ").append(token.getBaseValue()).append(";\n");
        }
        return sb.toString();
    }

    private String exportToAndroid(List<DesignToken> tokens) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
        for (DesignToken token : tokens) {
            String androidName = token.getTokenName().replace("--", "").replace("-", "_");
            if (token.getTokenType() != null && token.getTokenType().getCode().equals("color")) {
                sb.append("  <color name=\"").append(androidName).append("\">").append(token.getBaseValue()).append("</color>\n");
            } else {
                sb.append("  <dimen name=\"").append(androidName).append("\">").append(token.getBaseValue()).append("</dimen>\n");
            }
        }
        sb.append("</resources>\n");
        return sb.toString();
    }

    private String exportToIos(List<DesignToken> tokens) {
        StringBuilder sb = new StringBuilder("import UIKit\n\nenum DesignTokens {\n");
        for (DesignToken token : tokens) {
            String iosName = toCamelCase(token.getTokenName().replace("--", ""));
            if (token.getTokenType() != null && token.getTokenType().getCode().equals("color")) {
                sb.append("  static let ").append(iosName).append(" = UIColor(hex: \"").append(token.getBaseValue()).append("\")\n");
            } else {
                sb.append("  static let ").append(iosName).append(" = ").append(token.getBaseValue()).append("\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String toCamelCase(String name) {
        String[] parts = name.split("-");
        StringBuilder result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return result.toString();
    }

    public void clearCache() {
        try {
            Set<String> keys = redisTemplate.keys(CACHE_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            log.info("Token cache cleared");
        } catch (Exception e) {
            log.warn("Failed to clear token cache", e);
        }
    }
}
