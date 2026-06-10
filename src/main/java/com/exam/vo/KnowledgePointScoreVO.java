package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class KnowledgePointScoreVO {
    private Long knowledgePointId;
    private String knowledgePointName;
    private BigDecimal score;
    private BigDecimal totalScore;
    private BigDecimal accuracy;
    private Integer questionCount;
    private Integer correctCount;
}
