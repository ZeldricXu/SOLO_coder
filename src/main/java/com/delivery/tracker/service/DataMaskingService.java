package com.delivery.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delivery.tracker.entity.DataMaskingRule;
import com.delivery.tracker.mapper.DataMaskingRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataMaskingService {

    private final DataMaskingRuleMapper maskingRuleMapper;
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    public Mono<Map<String, Object>> maskData(Map<String, Object> data, Set<String> userRoles) {
        return Mono.fromCallable(() -> {
            Map<String, Object> maskedData = new ConcurrentHashMap<>(data);

            Flux.fromIterable(maskingRuleMapper.selectList(
                            new LambdaQueryWrapper<DataMaskingRule>()
                                    .eq(DataMaskingRule::getEnabled, true)
                    ))
                    .filter(rule -> shouldMask(rule, userRoles))
                    .doOnNext(rule -> applyMasking(maskedData, rule))
                    .blockLast();

            return maskedData;
        });
    }

    private boolean shouldMask(DataMaskingRule rule, Set<String> userRoles) {
        if (rule.getRequiredRole() == null || rule.getRequiredRole().isEmpty()) {
            return true;
        }
        return !userRoles.contains(rule.getRequiredRole());
    }

    private void applyMasking(Map<String, Object> data, DataMaskingRule rule) {
        String fieldName = rule.getFieldName();
        if (!data.containsKey(fieldName)) {
            return;
        }

        Object value = data.get(fieldName);
        if (!(value instanceof String)) {
            return;
        }

        String original = (String) value;
        String masked = switch (rule.getMaskType()) {
            case "FULL" -> maskFull(original);
            case "PARTIAL" -> maskPartial(original);
            case "EMAIL" -> maskEmail(original);
            case "PHONE" -> maskPhone(original);
            case "ID_CARD" -> maskIdCard(original);
            case "REGEX" -> maskRegex(original, rule);
            default -> original;
        };

        data.put(fieldName, masked);
        log.debug("字段脱敏: {} -> {}", fieldName, masked);
    }

    private String maskFull(String original) {
        return "*".repeat(Math.min(original.length(), 10));
    }

    private String maskPartial(String original) {
        if (original.length() <= 4) {
            return maskFull(original);
        }
        int visibleStart = 2;
        int visibleEnd = 2;
        return original.substring(0, visibleStart)
                + "*".repeat(original.length() - visibleStart - visibleEnd)
                + original.substring(original.length() - visibleEnd);
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return maskFull(email);
        }
        String username = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (username.length() <= 2) {
            return "*".repeat(username.length()) + domain;
        }
        return username.charAt(0) + "*".repeat(username.length() - 2) + username.charAt(username.length() - 1) + domain;
    }

    private String maskPhone(String phone) {
        if (phone.length() != 11) {
            return maskPartial(phone);
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private String maskIdCard(String idCard) {
        if (idCard.length() != 18) {
            return maskPartial(idCard);
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(14);
    }

    private String maskRegex(String original, DataMaskingRule rule) {
        if (rule.getPattern() == null || rule.getReplacement() == null) {
            return original;
        }
        Pattern pattern = patternCache.computeIfAbsent(rule.getPattern(), Pattern::compile);
        return pattern.matcher(original).replaceAll(rule.getReplacement());
    }

    public Mono<DataMaskingRule> createMaskingRule(DataMaskingRule rule) {
        return Mono.fromCallable(() -> {
            rule.setRuleId("mask_" + UUID.randomUUID().toString().substring(0, 8));
            maskingRuleMapper.insert(rule);
            log.info("脱敏规则创建成功: ruleId={}", rule.getRuleId());
            return rule;
        });
    }

    public Flux<DataMaskingRule> getAllMaskingRules() {
        return Flux.fromIterable(maskingRuleMapper.selectList(null));
    }

    public Mono<DataMaskingRule> updateMaskingRule(String ruleId, DataMaskingRule rule) {
        return Mono.fromCallable(() -> {
            DataMaskingRule existing = maskingRuleMapper.selectOne(
                    new LambdaQueryWrapper<DataMaskingRule>()
                            .eq(DataMaskingRule::getRuleId, ruleId)
            );
            if (existing == null) {
                throw new RuntimeException("脱敏规则不存在: " + ruleId);
            }
            existing.setFieldName(rule.getFieldName());
            existing.setMaskType(rule.getMaskType());
            existing.setRequiredRole(rule.getRequiredRole());
            existing.setPattern(rule.getPattern());
            existing.setReplacement(rule.getReplacement());
            existing.setEnabled(rule.getEnabled());
            maskingRuleMapper.updateById(existing);
            return existing;
        });
    }

    public Mono<Void> deleteMaskingRule(String ruleId) {
        return Mono.fromRunnable(() -> {
            DataMaskingRule existing = maskingRuleMapper.selectOne(
                    new LambdaQueryWrapper<DataMaskingRule>()
                            .eq(DataMaskingRule::getRuleId, ruleId)
            );
            if (existing != null) {
                maskingRuleMapper.deleteById(existing.getId());
                patternCache.remove(existing.getPattern());
            }
        });
    }
}
