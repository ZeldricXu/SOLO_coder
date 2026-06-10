package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_exam_session")
public class ExamSession extends BaseEntity {
    private Long examId;
    private Long paperId;
    private Long studentId;
    private Integer abType;
    private Integer sessionStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime submitTime;
    private Integer usedSeconds;
    private Integer screenSwitchCount;
    private Integer abnormalCount;
    private BigDecimal objectiveScore;
    private BigDecimal subjectiveScore;
    private BigDecimal totalScore;
    private Integer gradingStatus;
    private String gradingRemark;
    private String submitIp;
    private String deviceInfo;
    private LocalDateTime lastHeartbeat;
    private Integer reconnectCount;
}
