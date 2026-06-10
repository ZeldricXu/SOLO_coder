package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ClassScoreVO {
    private Long classId;
    private String className;
    private Long examId;
    private String examName;
    private Integer totalStudents;
    private Integer submittedCount;
    private Integer passCount;
    private BigDecimal passRate;
    private BigDecimal avgScore;
    private BigDecimal maxScore;
    private BigDecimal minScore;
    private List<StudentScoreVO> studentScores;
}
