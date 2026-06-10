package com.exam.service.arbitration;

import com.exam.entity.ExamAnswer;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

@Slf4j
public class MaxScoreArbitrationStrategy implements ArbitrationStrategy {

    @Override
    public String getStrategyName() {
        return "最高分仲裁策略";
    }

    @Override
    public String getStrategyCode() {
        return "MAX_SCORE";
    }

    @Override
    public BigDecimal arbitrate(ExamAnswer answer) {
        BigDecimal firstScore = answer.getFirstGraderScore();
        BigDecimal secondScore = answer.getSecondGraderScore();

        if (firstScore == null && secondScore == null) {
            return BigDecimal.ZERO;
        }
        if (firstScore == null) return secondScore;
        if (secondScore == null) return firstScore;

        BigDecimal max = firstScore.max(secondScore);
        log.debug("最高分仲裁: 一评={}, 二评={}, 结果={}", firstScore, secondScore, max);
        return max;
    }

    @Override
    public boolean supports(ExamAnswer answer) {
        return answer.getFirstGraderScore() != null || answer.getSecondGraderScore() != null;
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
