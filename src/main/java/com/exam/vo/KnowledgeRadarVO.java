package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class KnowledgeRadarVO {
    private List<String> labels;
    private List<BigDecimal> scores;
    private List<BigDecimal> maxScores;
    private List<BigDecimal> classAvgScores;
}
