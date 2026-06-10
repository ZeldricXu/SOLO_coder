package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_paper_template")
public class PaperTemplate extends BaseEntity {
    private String templateName;
    private Long subjectId;
    private Integer paperMode;
    private BigDecimal totalScore;
    private Integer totalMinutes;
    private Integer singleCount;
    private BigDecimal singleScore;
    private Integer multipleCount;
    private BigDecimal multipleScore;
    private Integer judgeCount;
    private BigDecimal judgeScore;
    private Integer fillCount;
    private BigDecimal fillScore;
    private Integer shortCount;
    private BigDecimal shortScore;
    private Integer programCount;
    private BigDecimal programScore;
    private BigDecimal easyRatio;
    private BigDecimal mediumRatio;
    private BigDecimal hardRatio;
    private String knowledgeDistribution;
    private String description;
    private Integer status;
}
