package com.exam.service.arbitration;

import com.exam.entity.ExamAnswer;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

@Slf4j
public class ManualArbitrationStrategy implements ArbitrationStrategy {

    @Override
    public String getStrategyName() {
        return "人工终裁策略";
    }

    @Override
    public String getStrategyCode() {
        return "MANUAL";
    }

    @Override
    public BigDecimal arbitrate(ExamAnswer answer) {
        log.debug("人工终裁策略: 一评={}, 二评={}, 需人工仲裁",
                answer.getFirstGraderScore(), answer.getSecondGraderScore());

        if (answer.getFinalScore() != null) {
            return answer.getFinalScore();
        }

        return null;
    }

    @Override
    public boolean supports(ExamAnswer answer) {
        return true;
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
