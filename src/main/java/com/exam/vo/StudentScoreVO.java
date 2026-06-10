package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StudentScoreVO {
    private Long userId;
    private String userName;
    private String realName;
    private BigDecimal score;
    private Integer rank;
    private Boolean isPass;
    private Integer usedTime;
}
