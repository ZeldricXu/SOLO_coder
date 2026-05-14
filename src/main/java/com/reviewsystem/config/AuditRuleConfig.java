package com.reviewsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.*;

@Configuration
@ConfigurationProperties(prefix = "review.audit")
public class AuditRuleConfig {

    private Rules rules = new Rules();

    public Rules getRules() {
        return rules;
    }

    public void setRules(Rules rules) {
        this.rules = rules;
    }

    public static class Rules {
        private List<String> sensitiveWords = new ArrayList<>();
        private QualityCheck qualityCheck = new QualityCheck();
        private Map<String, ViolationType> violationTypes = new LinkedHashMap<>();

        public List<String> getSensitiveWords() {
            return sensitiveWords;
        }

        public void setSensitiveWords(List<String> sensitiveWords) {
            this.sensitiveWords = sensitiveWords;
        }

        public Set<String> getSensitiveWordsSet() {
            return new HashSet<>(sensitiveWords);
        }

        public QualityCheck getQualityCheck() {
            return qualityCheck;
        }

        public void setQualityCheck(QualityCheck qualityCheck) {
            this.qualityCheck = qualityCheck;
        }

        public Map<String, ViolationType> getViolationTypes() {
            return violationTypes;
        }

        public void setViolationTypes(Map<String, ViolationType> violationTypes) {
            this.violationTypes = violationTypes;
        }

        public ViolationType getViolationType(String type) {
            return violationTypes.get(type);
        }
    }

    public static class QualityCheck {
        private int minLength = 5;
        private int maxLength = 2000;
        private int minQualityScore = 30;
        private SpamDetection spamDetection = new SpamDetection();

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(int minLength) {
            this.minLength = minLength;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        public int getMinQualityScore() {
            return minQualityScore;
        }

        public void setMinQualityScore(int minQualityScore) {
            this.minQualityScore = minQualityScore;
        }

        public SpamDetection getSpamDetection() {
            return spamDetection;
        }

        public void setSpamDetection(SpamDetection spamDetection) {
            this.spamDetection = spamDetection;
        }
    }

    public static class SpamDetection {
        private boolean enabled = true;
        private String phonePattern = "1[3-9]\\d{9}";
        private String urlPattern = "https?://[\\w-]+(\\.[\\w-]+)+[/#?]?.*";
        private double repeatThreshold = 0.7;
        private int sameWordMaxCount = 10;
        private String emailPattern = "[\\w.-]+@[\\w-]+\\.[\\w.-]+";
        private String qqPattern = "[1-9]\\d{4,10}";
        private String wechatPattern = "[a-zA-Z][a-zA-Z0-9_-]{5,19}";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPhonePattern() {
            return phonePattern;
        }

        public void setPhonePattern(String phonePattern) {
            this.phonePattern = phonePattern;
        }

        public String getUrlPattern() {
            return urlPattern;
        }

        public void setUrlPattern(String urlPattern) {
            this.urlPattern = urlPattern;
        }

        public double getRepeatThreshold() {
            return repeatThreshold;
        }

        public void setRepeatThreshold(double repeatThreshold) {
            this.repeatThreshold = repeatThreshold;
        }

        public int getSameWordMaxCount() {
            return sameWordMaxCount;
        }

        public void setSameWordMaxCount(int sameWordMaxCount) {
            this.sameWordMaxCount = sameWordMaxCount;
        }

        public String getEmailPattern() {
            return emailPattern;
        }

        public void setEmailPattern(String emailPattern) {
            this.emailPattern = emailPattern;
        }

        public String getQqPattern() {
            return qqPattern;
        }

        public void setQqPattern(String qqPattern) {
            this.qqPattern = qqPattern;
        }

        public String getWechatPattern() {
            return wechatPattern;
        }

        public void setWechatPattern(String wechatPattern) {
            this.wechatPattern = wechatPattern;
        }
    }

    public static class ViolationType {
        private String name;
        private int priority;
        private boolean autoReject;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public boolean isAutoReject() {
            return autoReject;
        }

        public void setAutoReject(boolean autoReject) {
            this.autoReject = autoReject;
        }
    }
}
