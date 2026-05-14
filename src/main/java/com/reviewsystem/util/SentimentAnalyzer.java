package com.reviewsystem.util;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SentimentAnalyzer {

    private final Set<String> positiveWords = new HashSet<>();
    private final Set<String> negativeWords = new HashSet<>();
    private final Map<String, Double> sentimentScores = new HashMap<>();

    public SentimentAnalyzer() {
        initSentimentLexicon();
    }

    private void initSentimentLexicon() {
        positiveWords.addAll(Arrays.asList(
                "好", "棒", "优秀", "精彩", "完美", "喜欢", "爱", "赞",
                "不错", "很好", "非常好", "太棒了", "厉害", "给力", "满意",
                "推荐", "支持", "感谢", "感激", "惊喜", "期待", "开心",
                "愉快", "享受", "价值", "实用", "方便", "简单", "清晰",
                "详细", "全面", "专业", "认真", "负责", "耐心", "友好"
        ));

        negativeWords.addAll(Arrays.asList(
                "差", "烂", "糟", "垃圾", "讨厌", "愤怒", "失望", "不满",
                "很差", "太差", "不好", "糟糕", "恶心", "骗人", "欺诈",
                "投诉", "退款", "退货", "问题", "故障", "卡顿", "崩溃",
                "复杂", "困难", "麻烦", "烦人", "生气", "不满", "后悔"
        ));

        sentimentScores.put("好", 0.7);
        sentimentScores.put("棒", 0.8);
        sentimentScores.put("优秀", 0.9);
        sentimentScores.put("精彩", 0.85);
        sentimentScores.put("完美", 1.0);
        sentimentScores.put("喜欢", 0.75);
        sentimentScores.put("爱", 0.9);
        sentimentScores.put("赞", 0.8);
        sentimentScores.put("不错", 0.6);
        sentimentScores.put("很好", 0.75);
        sentimentScores.put("非常好", 0.85);
        sentimentScores.put("太棒了", 0.9);
        sentimentScores.put("厉害", 0.8);
        sentimentScores.put("给力", 0.8);
        sentimentScores.put("满意", 0.7);
        sentimentScores.put("推荐", 0.75);
        sentimentScores.put("支持", 0.6);
        sentimentScores.put("感谢", 0.7);
        sentimentScores.put("感激", 0.8);
        sentimentScores.put("惊喜", 0.85);
        sentimentScores.put("期待", 0.6);
        sentimentScores.put("开心", 0.75);
        sentimentScores.put("愉快", 0.7);
        sentimentScores.put("享受", 0.7);
        sentimentScores.put("价值", 0.65);
        sentimentScores.put("实用", 0.7);
        sentimentScores.put("方便", 0.65);
        sentimentScores.put("简单", 0.6);
        sentimentScores.put("清晰", 0.65);
        sentimentScores.put("详细", 0.7);
        sentimentScores.put("全面", 0.65);
        sentimentScores.put("专业", 0.7);
        sentimentScores.put("认真", 0.65);
        sentimentScores.put("负责", 0.65);
        sentimentScores.put("耐心", 0.65);
        sentimentScores.put("友好", 0.65);

        sentimentScores.put("差", -0.7);
        sentimentScores.put("烂", -0.8);
        sentimentScores.put("糟", -0.8);
        sentimentScores.put("垃圾", -0.9);
        sentimentScores.put("讨厌", -0.75);
        sentimentScores.put("愤怒", -0.9);
        sentimentScores.put("失望", -0.7);
        sentimentScores.put("不满", -0.6);
        sentimentScores.put("很差", -0.85);
        sentimentScores.put("太差", -0.9);
        sentimentScores.put("不好", -0.6);
        sentimentScores.put("糟糕", -0.85);
        sentimentScores.put("恶心", -0.9);
        sentimentScores.put("骗人", -0.9);
        sentimentScores.put("欺诈", -0.95);
        sentimentScores.put("投诉", -0.7);
        sentimentScores.put("退款", -0.6);
        sentimentScores.put("退货", -0.6);
        sentimentScores.put("问题", -0.5);
        sentimentScores.put("故障", -0.7);
        sentimentScores.put("卡顿", -0.6);
        sentimentScores.put("崩溃", -0.8);
        sentimentScores.put("复杂", -0.5);
        sentimentScores.put("困难", -0.6);
        sentimentScores.put("麻烦", -0.6);
        sentimentScores.put("烦人", -0.6);
        sentimentScores.put("生气", -0.7);
        sentimentScores.put("后悔", -0.7);
    }

    public SentimentResult analyze(String text) {
        if (text == null || text.isEmpty()) {
            return new SentimentResult("neutral", 0.0, 0.0, 0.0, 1.0, new ArrayList<>());
        }

        List<String> foundKeywords = new ArrayList<>();
        double totalPositiveScore = 0.0;
        double totalNegativeScore = 0.0;
        int positiveCount = 0;
        int negativeCount = 0;

        for (String word : positiveWords) {
            if (text.contains(word)) {
                foundKeywords.add(word);
                totalPositiveScore += sentimentScores.getOrDefault(word, 0.7);
                positiveCount++;
            }
        }

        for (String word : negativeWords) {
            if (text.contains(word)) {
                foundKeywords.add(word);
                totalNegativeScore += Math.abs(sentimentScores.getOrDefault(word, -0.7));
                negativeCount++;
            }
        }

        double positiveScore = positiveCount > 0 ? totalPositiveScore / positiveCount : 0.0;
        double negativeScore = negativeCount > 0 ? totalNegativeScore / negativeCount : 0.0;

        double finalScore = positiveScore - negativeScore;
        double neutralScore = 1.0 - Math.abs(finalScore);
        if (neutralScore < 0) neutralScore = 0;

        String sentimentType;
        if (finalScore > 0.2) {
            sentimentType = "positive";
        } else if (finalScore < -0.2) {
            sentimentType = "negative";
        } else {
            sentimentType = "neutral";
        }

        double normalizedScore = (finalScore + 1.0) / 2.0;

        return new SentimentResult(
                sentimentType,
                normalizedScore,
                positiveScore,
                negativeScore,
                neutralScore,
                foundKeywords
        );
    }

    public static class SentimentResult {
        private final String sentimentType;
        private final double sentimentScore;
        private final double positiveScore;
        private final double negativeScore;
        private final double neutralScore;
        private final List<String> sentimentKeywords;

        public SentimentResult(String sentimentType, double sentimentScore,
                               double positiveScore, double negativeScore,
                               double neutralScore, List<String> sentimentKeywords) {
            this.sentimentType = sentimentType;
            this.sentimentScore = sentimentScore;
            this.positiveScore = positiveScore;
            this.negativeScore = negativeScore;
            this.neutralScore = neutralScore;
            this.sentimentKeywords = sentimentKeywords;
        }

        public String getSentimentType() {
            return sentimentType;
        }

        public double getSentimentScore() {
            return sentimentScore;
        }

        public double getPositiveScore() {
            return positiveScore;
        }

        public double getNegativeScore() {
            return negativeScore;
        }

        public double getNeutralScore() {
            return neutralScore;
        }

        public List<String> getSentimentKeywords() {
            return sentimentKeywords;
        }
    }
}
