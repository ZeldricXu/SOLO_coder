package com.reviewsystem.rule;

import com.reviewsystem.config.AuditRuleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class AuditRuleManager {

    private static final Logger log = LoggerFactory.getLogger(AuditRuleManager.class);

    @Resource
    private AuditRuleConfig auditRuleConfig;

    private volatile Set<String> sensitiveWordsCache;
    private volatile Map<String, Pattern> spamPatternCache;
    private volatile Map<String, AuditRuleConfig.ViolationType> violationTypeCache;

    @PostConstruct
    public void init() {
        refreshRules();
        log.info("审核规则管理器初始化完成，敏感词数量: {}, 违规类型: {}",
                sensitiveWordsCache.size(), violationTypeCache.size());
    }

    public void refreshRules() {
        sensitiveWordsCache = auditRuleConfig.getRules().getSensitiveWordsSet();

        spamPatternCache = new ConcurrentHashMap<>();
        AuditRuleConfig.SpamDetection spamDetection = auditRuleConfig.getRules().getQualityCheck().getSpamDetection();

        spamPatternCache.put("phone", Pattern.compile(spamDetection.getPhonePattern()));
        spamPatternCache.put("url", Pattern.compile(spamDetection.getUrlPattern(), Pattern.CASE_INSENSITIVE));
        spamPatternCache.put("email", Pattern.compile(spamDetection.getEmailPattern(), Pattern.CASE_INSENSITIVE));
        spamPatternCache.put("qq", Pattern.compile(spamDetection.getQqPattern()));
        spamPatternCache.put("wechat", Pattern.compile(spamDetection.getWechatPattern(), Pattern.CASE_INSENSITIVE));

        violationTypeCache = new ConcurrentHashMap<>(auditRuleConfig.getRules().getViolationTypes());

        log.info("审核规则已刷新");
    }

    public Set<String> getSensitiveWords() {
        return Collections.unmodifiableSet(sensitiveWordsCache);
    }

    public boolean containsSensitiveWord(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String lowerContent = content.toLowerCase();
        for (String word : sensitiveWordsCache) {
            if (lowerContent.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public Set<String> findSensitiveWords(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> found = new HashSet<>();
        String lowerContent = content.toLowerCase();
        for (String word : sensitiveWordsCache) {
            if (lowerContent.contains(word.toLowerCase())) {
                found.add(word);
            }
        }
        return found;
    }

    public boolean isSpamContent(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        AuditRuleConfig.SpamDetection spamDetection = auditRuleConfig.getRules().getQualityCheck().getSpamDetection();
        if (!spamDetection.isEnabled()) {
            return false;
        }

        for (Map.Entry<String, Pattern> entry : spamPatternCache.entrySet()) {
            if (entry.getValue().matcher(content).find()) {
                return true;
            }
        }

        return hasExcessiveRepetition(content, spamDetection.getSameWordMaxCount());
    }

    private boolean hasExcessiveRepetition(String content, int maxCount) {
        Map<String, Integer> wordCount = new HashMap<>();
        String[] words = content.split("");
        for (String word : words) {
            if (word.trim().isEmpty()) continue;
            wordCount.merge(word, 1, Integer::sum);
            if (wordCount.get(word) > maxCount) {
                return true;
            }
        }
        return false;
    }

    public int getMinLength() {
        return auditRuleConfig.getRules().getQualityCheck().getMinLength();
    }

    public int getMaxLength() {
        return auditRuleConfig.getRules().getQualityCheck().getMaxLength();
    }

    public int getMinQualityScore() {
        return auditRuleConfig.getRules().getQualityCheck().getMinQualityScore();
    }

    public AuditRuleConfig.ViolationType getViolationType(String type) {
        return violationTypeCache.get(type);
    }

    public Map<String, AuditRuleConfig.ViolationType> getAllViolationTypes() {
        return Collections.unmodifiableMap(violationTypeCache);
    }

    public boolean isAutoRejectViolation(String type) {
        AuditRuleConfig.ViolationType vt = violationTypeCache.get(type);
        return vt != null && vt.isAutoReject();
    }

    public AuditRuleConfig.QualityCheck getQualityCheckConfig() {
        return auditRuleConfig.getRules().getQualityCheck();
    }

    public AuditRuleConfig.SpamDetection getSpamDetectionConfig() {
        return auditRuleConfig.getRules().getQualityCheck().getSpamDetection();
    }

    public void addSensitiveWord(String word) {
        auditRuleConfig.getRules().getSensitiveWords().add(word);
        refreshRules();
        log.info("添加敏感词: {}", word);
    }

    public void removeSensitiveWord(String word) {
        auditRuleConfig.getRules().getSensitiveWords().remove(word);
        refreshRules();
        log.info("移除敏感词: {}", word);
    }

    public void addViolationType(String type, AuditRuleConfig.ViolationType definition) {
        auditRuleConfig.getRules().getViolationTypes().put(type, definition);
        refreshRules();
        log.info("添加违规类型: {}", type);
    }

    public void removeViolationType(String type) {
        auditRuleConfig.getRules().getViolationTypes().remove(type);
        refreshRules();
        log.info("移除违规类型: {}", type);
    }
}
