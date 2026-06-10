package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_exam_answer")
public class ExamAnswer extends BaseEntity {
    private Long sessionId;
    private Long examId;
    private Long paperId;
    private Long questionId;
    private Long studentId;
    private Integer questionOrder;
    private Integer questionType;
    private String studentAnswer;
    private String correctAnswer;
    private BigDecimal questionScore;
    private BigDecimal studentScore;
    private Integer answerStatus;
    private Integer gradingStatus;
    private Long firstGraderId;
    private BigDecimal firstGraderScore;
    private String firstGraderRemark;
    private LocalDateTime firstGradeTime;
    private Long secondGraderId;
    private BigDecimal secondGraderScore;
    private String secondGraderRemark;
    private LocalDateTime secondGradeTime;
    private Long arbitratorId;
    private BigDecimal finalScore;
    private String arbitrationRemark;
    private LocalDateTime arbitrationTime;
    private String judgeLog;
    private String codeOutput;
    private LocalDateTime lastSaveTime;
}
