package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_grading_record")
public class GradingRecord extends BaseEntity {
    private Long examId;
    private Long examRecordId;
    private Long questionId;
    private Long answerId;
    private Long graderId;
    private Integer gradingType;
    private BigDecimal score;
    private BigDecimal maxScore;
    private String gradingRemark;
    private Integer gradingStatus;
    private LocalDateTime gradingTime;
    private Integer isArbitration;
    private Long arbitrationGraderId;
    private BigDecimal arbitrationScore;
    private String arbitrationRemark;
}
