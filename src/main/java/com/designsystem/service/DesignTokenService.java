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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.designsystem.config.RabbitMQConfig.*;

@Service
public class DesignTokenService {

    private final DesignTokenMapper tokenMapper;
    private final TokenOverrideMapper overrideMapper;
    private final ComponentTokenUsageMapper usageMapper;
    private final TokenChangeMapper changeMapper;
    private final ComponentMapper componentMapper;
    private final RabbitTemplate rabbitTemplate;
    private TokenInheritanceUtil inheritanceUtil;

    public DesignTokenService(DesignTokenMapper tokenMapper, TokenOverrideMapper overrideMapper,
                              ComponentTokenUsageMapper usageMapper, TokenChangeMapper changeMapper,
                              ComponentMapper componentMapper, RabbitTemplate rabbitTemplate) {
        this.tokenMapper = tokenMapper;
        this.overrideMapper = overrideMapper;
        this.usageMapper = usageMapper;
        this.changeMapper = changeMapper;
        this.componentMapper = componentMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void init() {
        this.inheritanceUtil = new TokenInheritanceUtil(tokenMapper);
    }

    public IPage<DesignToken> getTokenPage(PageQuery query, String tokenType, String tokenLevel, String category) {
        Page<DesignToken> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<DesignToken> result = tokenMapper.selectTokenPage(page, query.getKeyword(), tokenType, tokenLevel, category);
        result.getRecords().forEach(this::enrichToken);
        return result;
    }

    public DesignToken getTokenById(Long id) {
        DesignToken token = tokenMapper.selectById(id);
        if (token != null) {
            enrichToken(token);
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
        List<DesignToken> children = tokenMapper.selectByParentId(token.getTokenName());
        token.setChildTokens(children);
        children.forEach(this::buildTokenTree);
    }

    @Transactional(rollbackFor = Exception.class)
    public DesignToken createToken(DesignToken token) {
        token.setStatus(1);
        tokenMapper.insert(token);
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

        Map<String, Object> changeEvent = new HashMap<>();
        changeEvent.put("tokenId", token.getId());
        changeEvent.put("changeId", change.getId());
        rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_TOKEN_CHANGE, changeEvent);

        return token;
    }

    public String exportTokens(ExportFormat format, String tokenType, String tokenLevel) {
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

        return switch (format) {
            case CSS -> exportToCss(tokens);
            case JS -> exportToJs(tokens);
            case JSON -> exportToJson(tokens);
            case SCSS -> exportToScss(tokens);
            case LESS -> exportToLess(tokens);
            case ANDROID -> exportToAndroid(tokens);
            case IOS -> exportToIos(tokens);
        };
    }

    public List<Component> getAffectedComponents(Long tokenId) {
        List<DesignToken> tokenChain = tokenMapper.selectTokenChain(tokenId);
        Set<Long> tokenIds = tokenChain.stream().map(DesignToken::getId).collect(Collectors.toSet());
        tokenIds.add(tokenId);

        Set<Long> componentIds = new HashSet<>();
        for (Long tid : tokenIds) {
            usageMapper.selectByTokenId(tid).forEach(usage -> componentIds.add(usage.getComponentId()));
        }

        return componentIds.isEmpty() ? new ArrayList<>() : componentMapper.selectBatchIds(componentIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public TokenOverride addOverride(TokenOverride override) {
        overrideMapper.insert(override);
        return override;
    }

    public List<TokenOverride> getOverridesByTokenId(Long tokenId) {
        return overrideMapper.selectByTokenId(tokenId);
    }

    public Map<String, Object> getTokenImpactAnalysis(Long tokenId) {
        DesignToken token = getTokenById(tokenId);
        List<Component> affectedComponents = getAffectedComponents(tokenId);
        List<DesignToken> affectedTokens = tokenMapper.selectByParentId(token.getTokenName());
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
        return inheritanceUtil.resolveTokenValue(tokenName);
    }

    public Set<String> getAffectedTokens(String modifiedTokenName) {
        return inheritanceUtil.getAffectedTokens(modifiedTokenName);
    }

    public Set<String> getInheritanceChain(String tokenName) {
        return inheritanceUtil.getInheritanceChain(tokenName);
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
}
