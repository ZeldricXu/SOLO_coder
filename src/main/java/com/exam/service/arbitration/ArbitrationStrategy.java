package com.exam.service.arbitration;

import com.exam.entity.ExamAnswer;

import java.math.BigDecimal;

public interface ArbitrationStrategy {

    String getStrategyName();

    default String getStrategyCode() {
        return "AVERAGE";
    }

    BigDecimal arbitrate(ExamAnswer answer);

    boolean supports(ExamAnswer answer);

    default int getOrder() {
        return 0;
    }
}
