package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_answer")
public class ExamAnswer extends BaseEntity {
    private Long examId;
    private Long examRecordId;
    private Long userId;
    private Long paperId;
    private Long questionId;
    private Integer questionType;
    private Integer questionOrder;
    private BigDecimal questionScore;
    private String userAnswer;
    private String correctAnswer;
    private BigDecimal score;
    private Integer answerStatus;
    private Integer isCorrect;
    private Integer gradingStatus;
    private String gradingRemark;
    private LocalDateTime answerTime;
    private Integer sortOrder;
}
