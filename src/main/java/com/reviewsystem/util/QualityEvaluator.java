package com.reviewsystem.util;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class QualityEvaluator {

    private static final int MIN_CONTENT_LENGTH = 5;
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int OPTIMAL_MIN_LENGTH = 10;
    private static final int OPTIMAL_MAX_LENGTH = 500;

    private static final Pattern SPAM_PATTERN = Pattern.compile(
            "https?://|www\\.|[a-zA-Z0-9]+\\.com|qq|微信|vx|vx:|加我|联系我",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern REPEAT_CHAR_PATTERN = Pattern.compile(
            "(.)\\1{5,}"
    );

    private static final Set<String> MEANINGLESS_WORDS = new HashSet<>(Arrays.asList(
            "沙发", "板凳", "地板", "路过", "看看", "呵呵", "哈哈",
            "嗯嗯", "哦哦", "额", "啊", "哦", "嗯", "哈", "顶", "up"
    ));

    public QualityResult evaluate(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new QualityResult(0, 0, 0, 0, 0,
                    false, "empty", "评论内容为空",
                    false, "invalid");
        }

        String trimmedContent = content.trim();
        int length = trimmedContent.length();

        int lengthScore = calculateLengthScore(length);
        int relevanceScore = calculateRelevanceScore(trimmedContent);
        int readabilityScore = calculateReadabilityScore(trimmedContent);

        boolean isSpam = detectSpam(trimmedContent);
        int spamScore = isSpam ? 80 : 0;

        int violationScore = isSpam ? 90 : 0;
        boolean isViolation = isSpam;
        String violationType = isSpam ? "spam" : null;
        String violationReason = isSpam ? "检测到疑似垃圾评论内容" : null;

        int totalScore = (lengthScore + relevanceScore + readabilityScore) / 3;
        if (isSpam) {
            totalScore = Math.max(10, totalScore - 50);
        }

        String evaluationLevel;
        if (totalScore >= 80) {
            evaluationLevel = "excellent";
        } else if (totalScore >= 60) {
            evaluationLevel = "good";
        } else if (totalScore >= 40) {
            evaluationLevel = "medium";
        } else if (totalScore >= 20) {
            evaluationLevel = "low";
        } else {
            evaluationLevel = "poor";
        }

        return new QualityResult(
                totalScore, lengthScore, relevanceScore, readabilityScore,
                violationScore, isViolation, violationType, violationReason,
                isSpam, evaluationLevel
        );
    }

    private int calculateLengthScore(int length) {
        if (length < MIN_CONTENT_LENGTH) {
            return 10;
        }
        if (length < OPTIMAL_MIN_LENGTH) {
            return 30 + (length - MIN_CONTENT_LENGTH) * 5;
        }
        if (length <= OPTIMAL_MAX_LENGTH) {
            int optimalMid = (OPTIMAL_MIN_LENGTH + OPTIMAL_MAX_LENGTH) / 2;
            int distance = Math.abs(length - optimalMid);
            int maxDistance = optimalMid - OPTIMAL_MIN_LENGTH;
            return 100 - (distance * 20 / maxDistance);
        }
        if (length <= MAX_CONTENT_LENGTH) {
            return Math.max(50, 100 - (length - OPTIMAL_MAX_LENGTH) / 30);
        }
        return 40;
    }

    private int calculateRelevanceScore(String content) {
        int score = 100;

        int meaninglessCount = 0;
        for (String word : MEANINGLESS_WORDS) {
            if (content.toLowerCase().contains(word.toLowerCase())) {
                meaninglessCount++;
            }
        }
        score -= meaninglessCount * 10;

        int uniqueChars = (int) content.chars().distinct().count();
        double uniqueRatio = (double) uniqueChars / content.length();
        if (uniqueRatio < 0.3) {
            score -= 30;
        } else if (uniqueRatio < 0.5) {
            score -= 15;
        }

        return Math.max(0, score);
    }

    private int calculateReadabilityScore(String content) {
        int score = 100;

        if (REPEAT_CHAR_PATTERN.matcher(content).find()) {
            score -= 20;
        }

        int sentenceCount = content.split("[。！？.!?]").length;
        if (sentenceCount < 2 && content.length() > 100) {
            score -= 15;
        }

        return Math.max(0, score);
    }

    private boolean detectSpam(String content) {
        if (SPAM_PATTERN.matcher(content).find()) {
            return true;
        }

        String lowerContent = content.toLowerCase();
        if (lowerContent.contains("广告") || lowerContent.contains("推广")) {
            return true;
        }

        String[] lines = content.split("\n");
        if (lines.length > 5) {
            Set<String> uniqueLines = new HashSet<>(Arrays.asList(lines));
            if ((double) uniqueLines.size() / lines.length < 0.5) {
                return true;
            }
        }

        return false;
    }

    public static class QualityResult {
        private final int qualityScore;
        private final int lengthScore;
        private final int relevanceScore;
        private final int readabilityScore;
        private final int violationScore;
        private final boolean isViolation;
        private final String violationType;
        private final String violationReason;
        private final boolean isSpam;
        private final String evaluationLevel;

        public QualityResult(int qualityScore, int lengthScore, int relevanceScore,
                            int readabilityScore, int violationScore,
                            boolean isViolation, String violationType, String violationReason,
                            boolean isSpam, String evaluationLevel) {
            this.qualityScore = qualityScore;
            this.lengthScore = lengthScore;
            this.relevanceScore = relevanceScore;
            this.readabilityScore = readabilityScore;
            this.violationScore = violationScore;
            this.isViolation = isViolation;
            this.violationType = violationType;
            this.violationReason = violationReason;
            this.isSpam = isSpam;
            this.evaluationLevel = evaluationLevel;
        }

        public int getQualityScore() {
            return qualityScore;
        }

        public int getLengthScore() {
            return lengthScore;
        }

        public int getRelevanceScore() {
            return relevanceScore;
        }

        public int getReadabilityScore() {
            return readabilityScore;
        }

        public int getViolationScore() {
            return violationScore;
        }

        public boolean isViolation() {
            return isViolation;
        }

        public String getViolationType() {
            return violationType;
        }

        public String getViolationReason() {
            return violationReason;
        }

        public boolean isSpam() {
            return isSpam;
        }

        public String getEvaluationLevel() {
            return evaluationLevel;
        }
    }
}
