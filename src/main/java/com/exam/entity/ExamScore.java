package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_score")
public class ExamScore extends BaseEntity {
    private Long examId;
    private Long sessionId;
    private Long paperId;
    private Long studentId;
    private Long classId;
    private Long subjectId;
    private BigDecimal totalScore;
    private BigDecimal objectiveScore;
    private BigDecimal subjectiveScore;
    private BigDecimal programScore;
    private Integer rank;
    private BigDecimal percentile;
    private String knowledgeMastery;
    private String wrongQuestions;
    private LocalDateTime publishTime;
    private Integer published;
    private String scoreRemark;
}
