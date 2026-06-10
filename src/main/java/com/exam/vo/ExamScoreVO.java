package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ExamScoreVO {
    private Long examId;
    private String examName;
    private BigDecimal score;
    private Integer rank;
    private Boolean isPass;
    private LocalDateTime examTime;
}
