package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_paper_template")
public class PaperTemplate extends BaseEntity {
    private String templateName;
    private String templateCode;
    private Long subjectId;
    private Integer paperMode;
    private Integer totalScore;
    private Integer totalQuestions;
    private Integer duration;
    private Integer passScore;
    private String difficultyConfig;
    private String knowledgeConfig;
    private String questionTypeConfig;
    private String description;
    private Integer status;
    private LocalDateTime effectiveStartTime;
    private LocalDateTime effectiveEndTime;
}
