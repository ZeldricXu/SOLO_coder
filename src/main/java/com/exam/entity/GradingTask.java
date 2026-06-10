package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_grading_task")
public class GradingTask extends BaseEntity {
    private Long examId;
    private Long sessionId;
    private Long answerId;
    private Long questionId;
    private Long studentId;
    private Long graderId;
    private Integer gradingRound;
    private BigDecimal questionScore;
    private BigDecimal graderScore;
    private String graderRemark;
    private Integer taskStatus;
    private LocalDateTime assignTime;
    private LocalDateTime deadline;
    private LocalDateTime gradeTime;
    private Integer timeoutCount;
    private Integer arbitrationRequired;
    private String blindCode;
}
