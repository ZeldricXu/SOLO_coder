package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_paper")
public class Paper extends BaseEntity {
    private String paperName;
    private String paperCode;
    private Long templateId;
    private Long subjectId;
    private Integer paperMode;
    private Integer paperVersion;
    private Integer totalScore;
    private Integer totalQuestions;
    private Integer duration;
    private Integer passScore;
    private BigDecimal difficultyAvg;
    private String questionIds;
    private String difficultyConfig;
    private String knowledgeConfig;
    private String description;
    private Integer status;
    private Integer isTemplate;
    private LocalDateTime effectiveStartTime;
    private LocalDateTime effectiveEndTime;
}
