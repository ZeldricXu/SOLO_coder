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
    private Long subjectId;
    private Long templateId;
    private Integer paperMode;
    private String paperVersion;
    private Integer abType;
    private BigDecimal totalScore;
    private Integer totalMinutes;
    private Integer questionCount;
    private String questionOrder;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer allowLateSubmit;
    private Integer lateSubmitMinutes;
    private Integer antiCheatingLevel;
    private Integer maxScreenSwitch;
    private String description;
    private Integer status;
    private Long publishBy;
    private LocalDateTime publishTime;
}
