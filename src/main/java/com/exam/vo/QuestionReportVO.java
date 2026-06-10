package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QuestionReportVO {
    private Long questionId;
    private Integer questionType;
    private String questionTypeText;
    private BigDecimal questionScore;
    private BigDecimal score;
    private Boolean isCorrect;
    private String userAnswer;
    private String correctAnswer;
    private String analysis;
    private Integer questionOrder;
}
