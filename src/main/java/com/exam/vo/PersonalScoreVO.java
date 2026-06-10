package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PersonalScoreVO {
    private Long userId;
    private String userName;
    private Integer totalExamCount;
    private Integer passCount;
    private BigDecimal passRate;
    private BigDecimal avgScore;
    private BigDecimal highestScore;
    private BigDecimal lowestScore;
    private List<ExamScoreVO> examScores;
}
