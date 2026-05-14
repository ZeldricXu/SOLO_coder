package com.mobilestore.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class FeedbackClassificationService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CLASSIFICATION_CACHE_KEY = "feedback:classification_rules";
    private static final String CATEGORY_CACHE_KEY = "feedback:category_keywords";

    public static final String TYPE_BUG_REPORT = "bug_report";
    public static final String TYPE_FEATURE_REQUEST = "feature_request";
    public static final String TYPE_COMPLAINT = "complaint";
    public static final String TYPE_QUESTION = "question";
    public static final String TYPE_OTHER = "other";

    public static final String PRIORITY_HIGH = "high";
    public static final String PRIORITY_MEDIUM = "medium";
    public static final String PRIORITY_LOW = "low";

    public static final String ASSIGNEE_TECH = "tech_support_001";
    public static final String ASSIGNEE_PRODUCT = "product_001";
    public static final String ASSIGNEE_SUPPORT = "support_001";

    private final Map<String, List<Pattern>> keywordPatterns = new HashMap<>();
    private final Map<String, List<String>> categoryKeywords = new HashMap<>();

    public FeedbackClassificationService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void initClassificationRules() {
        Map<String, List<String>> rules = new HashMap<>();
        
        List<String> bugKeywords = Arrays.asList(
            "闪退", "崩溃", "crash", "闪退", "无法启动", "启动失败",
            "报错", "异常", "error", "exception", "bug",
            "黑屏", "白屏", "卡死", "无响应", "not responding",
            "功能失效", "功能没反应", "点击没反应", "按钮没反应",
            "数据丢失", "保存失败", "上传失败", "下载失败",
            "网络错误", "连接失败", "timeout", "超时",
            "登录失败", "登录取消", "密码错误", "验证失败",
            "支付失败", "付款失败", "扣款失败", "订单异常",
            "兼容性问题", "适配问题", "分辨率问题",
            "版本问题", "更新失败", "升级失败"
        );
        rules.put(TYPE_BUG_REPORT, bugKeywords);

        List<String> featureKeywords = Arrays.asList(
            "建议", "建议", "希望", "希望能", "能不能", "能不能增加",
            "功能建议", "新增功能", "增加功能", "添加功能",
            "需求", "希望有", "想要", "期待", "expect",
            "优化", "改进", "improve", "better",
            "体验", "用户体验", "界面", "UI", "UX",
            "设置", "选项", "配置", "自定义",
            "支持", "希望支持", "能否支持", "是否可以",
            "多语言", "国际化", "i18n",
            "深色模式", "夜间模式", "dark mode",
            "导出", "分享", "同步", "备份"
        );
        rules.put(TYPE_FEATURE_REQUEST, featureKeywords);

        List<String> complaintKeywords = Arrays.asList(
            "投诉", "投诉", "太烂了", "垃圾", "差评",
            "很差", "不好用", "难用", "使用不便",
            "太慢", "速度慢", "卡顿", "lag",
            "收费", "太贵", "价格", "付费", "pay",
            "广告太多", "广告烦人", "推送太多",
            "骚扰", "垃圾短信", "spam",
            "隐私问题", "安全问题", "窃取", "泄露"
        );
        rules.put(TYPE_COMPLAINT, complaintKeywords);

        List<String> questionKeywords = Arrays.asList(
            "怎么", "如何", "如何", "怎么弄", "如何使用",
            "请问", "想问", "求助", "help", "请问",
            "什么意思", "什么是", "含义", "解释",
            "使用方法", "教程", "说明", "文档",
            "在哪里", "怎么找", "找不到",
            "为什么", "原因", "怎么回事"
        );
        rules.put(TYPE_QUESTION, questionKeywords);

        categoryKeywords.clear();
        categoryKeywords.putAll(rules);

        keywordPatterns.clear();
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            List<Pattern> patterns = new ArrayList<>();
            for (String keyword : entry.getValue()) {
                patterns.add(Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE));
            }
            keywordPatterns.put(entry.getKey(), patterns);
        }

        try {
            redisTemplate.opsForValue().set(CLASSIFICATION_CACHE_KEY, keywordPatterns.keySet());
            redisTemplate.opsForValue().set(CATEGORY_CACHE_KEY, categoryKeywords);
        } catch (Exception e) {
        }
    }

    public ClassificationResult classify(String feedbackType, String content, Integer rating) {
        String finalType = feedbackType;
        String priority = PRIORITY_LOW;
        String assignee = ASSIGNEE_SUPPORT;
        List<String> matchedKeywords = new ArrayList<>();

        if (finalType == null || finalType.isEmpty()) {
            finalType = inferTypeFromContent(content);
        }

        matchedKeywords = findMatchedKeywords(content, finalType);

        priority = determinePriority(finalType, rating, matchedKeywords, content);
        assignee = determineAssignee(finalType, priority);

        return new ClassificationResult(
            finalType,
            priority,
            assignee,
            matchedKeywords
        );
    }

    private String inferTypeFromContent(String content) {
        if (content == null || content.isEmpty()) {
            return TYPE_OTHER;
        }

        Map<String, Integer> typeScores = new HashMap<>();
        typeScores.put(TYPE_BUG_REPORT, 0);
        typeScores.put(TYPE_FEATURE_REQUEST, 0);
        typeScores.put(TYPE_COMPLAINT, 0);
        typeScores.put(TYPE_QUESTION, 0);

        for (Map.Entry<String, List<Pattern>> entry : keywordPatterns.entrySet()) {
            int score = 0;
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(content).find()) {
                    score++;
                }
            }
            typeScores.put(entry.getKey(), score);
        }

        String maxType = TYPE_OTHER;
        int maxScore = 0;
        for (Map.Entry<String, Integer> entry : typeScores.entrySet()) {
            if (entry.getValue() > maxScore) {
                maxScore = entry.getValue();
                maxType = entry.getKey();
            }
        }

        return maxScore > 0 ? maxType : TYPE_OTHER;
    }

    private List<String> findMatchedKeywords(String content, String type) {
        List<String> matched = new ArrayList<>();
        if (content == null) return matched;

        List<String> keywords = categoryKeywords.getOrDefault(type, new ArrayList<>());
        for (String keyword : keywords) {
            if (content.toLowerCase().contains(keyword.toLowerCase())) {
                matched.add(keyword);
            }
        }
        return matched;
    }

    private String determinePriority(String type, Integer rating, List<String> matchedKeywords, String content) {
        if (TYPE_BUG_REPORT.equals(type)) {
            boolean isCritical = matchedKeywords.stream().anyMatch(k -> 
                Arrays.asList("闪退", "崩溃", "crash", "无法启动", "数据丢失", "支付失败").contains(k)
            ) || (content != null && (
                content.contains("无法使用") || 
                content.contains("完全不能用") ||
                content.contains("紧急")
            ));
            return isCritical ? PRIORITY_HIGH : PRIORITY_MEDIUM;
        }

        if (TYPE_COMPLAINT.equals(type)) {
            if (rating != null && rating <= 2) {
                return PRIORITY_HIGH;
            }
            return PRIORITY_MEDIUM;
        }

        if (TYPE_FEATURE_REQUEST.equals(type)) {
            return PRIORITY_MEDIUM;
        }

        if (TYPE_QUESTION.equals(type)) {
            return PRIORITY_LOW;
        }

        if (rating != null) {
            if (rating <= 2) return PRIORITY_HIGH;
            if (rating <= 3) return PRIORITY_MEDIUM;
        }

        return PRIORITY_LOW;
    }

    private String determineAssignee(String type, String priority) {
        if (TYPE_BUG_REPORT.equals(type)) {
            return ASSIGNEE_TECH;
        }

        if (TYPE_FEATURE_REQUEST.equals(type)) {
            return ASSIGNEE_PRODUCT;
        }

        if (TYPE_COMPLAINT.equals(type) && PRIORITY_HIGH.equals(priority)) {
            return ASSIGNEE_SUPPORT;
        }

        if (TYPE_QUESTION.equals(type)) {
            return ASSIGNEE_SUPPORT;
        }

        return ASSIGNEE_SUPPORT;
    }

    public Map<String, List<String>> getCategoryKeywords() {
        return new HashMap<>(categoryKeywords);
    }

    public static class ClassificationResult {
        private final String feedbackType;
        private final String priority;
        private final String assignee;
        private final List<String> matchedKeywords;

        public ClassificationResult(String feedbackType, String priority, String assignee, List<String> matchedKeywords) {
            this.feedbackType = feedbackType;
            this.priority = priority;
            this.assignee = assignee;
            this.matchedKeywords = matchedKeywords;
        }

        public String getFeedbackType() { return feedbackType; }
        public String getPriority() { return priority; }
        public String getAssignee() { return assignee; }
        public List<String> getMatchedKeywords() { return matchedKeywords; }
    }
}
