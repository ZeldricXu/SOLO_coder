package com.exam.service.arbitration;

import com.exam.entity.ExamAnswer;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
public class AverageArbitrationStrategy implements ArbitrationStrategy {

    private static final BigDecimal DEFAULT_DIFF_RATIO = new BigDecimal("0.2");

    private BigDecimal diffRatio;

    public AverageArbitrationStrategy() {
        this(DEFAULT_DIFF_RATIO);
    }

    public AverageArbitrationStrategy(BigDecimal diffRatio) {
        this.diffRatio = diffRatio != null ? diffRatio : DEFAULT_DIFF_RATIO;
    }

    @Override
    public String getStrategyName() {
        return "均值仲裁策略";
    }

    @Override
    public String getStrategyCode() {
        return "AVERAGE";
    }

    @Override
    public BigDecimal arbitrate(ExamAnswer answer) {
        BigDecimal firstScore = answer.getFirstGraderScore();
        BigDecimal secondScore = answer.getSecondGraderScore();
        BigDecimal questionScore = answer.getQuestionScore();

        if (firstScore == null && secondScore == null) {
            return BigDecimal.ZERO;
        }
        if (firstScore == null) return secondScore;
        if (secondScore == null) return firstScore;

        BigDecimal diff = firstScore.subtract(secondScore).abs();
        BigDecimal threshold = questionScore.multiply(diffRatio);

        if (diff.compareTo(threshold) <= 0) {
            BigDecimal result = firstScore.add(secondScore)
                    .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            log.debug("均值仲裁: 一评={}, 二评={}, 分差={}, 阈值={}, 结果={}",
                    firstScore, secondScore, diff, threshold, result);
            return result;
        }

        log.debug("均值仲裁触发人工仲裁: 一评={}, 二评={}, 分差={} > 阈值={}",
                firstScore, secondScore, diff, threshold);
        return null;
    }

    @Override
    public boolean supports(ExamAnswer answer) {
        return answer.getFirstGraderScore() != null || answer.getSecondGraderScore() != null;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    public BigDecimal getDiffRatio() {
        return diffRatio;
    }

    public void setDiffRatio(BigDecimal diffRatio) {
        this.diffRatio = diffRatio;
    }
}
