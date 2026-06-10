package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ExamStatisticsVO {
    private Long examId;
    private String examName;
    private Integer totalCandidates;
    private Integer submittedCount;
    private Integer gradedCount;
    private Integer passCount;
    private BigDecimal passRate;
    private BigDecimal avgScore;
    private BigDecimal maxScore;
    private BigDecimal minScore;
    private BigDecimal medianScore;
    private BigDecimal standardDeviation;
}
