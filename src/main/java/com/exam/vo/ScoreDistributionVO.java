package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ScoreDistributionVO {
    private String scoreRange;
    private Integer count;
    private BigDecimal percentage;
}
