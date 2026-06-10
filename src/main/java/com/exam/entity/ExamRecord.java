package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_record")
public class ExamRecord extends BaseEntity {
    private Long examId;
    private Long userId;
    private Long paperId;
    private String paperVersion;
    private LocalDateTime startTime;
    private LocalDateTime submitTime;
    private LocalDateTime endTime;
    private Integer duration;
    private Integer usedTime;
    private Integer examStatus;
    private Integer gradingStatus;
    private BigDecimal totalScore;
    private BigDecimal objectiveScore;
    private BigDecimal subjectiveScore;
    private BigDecimal programmingScore;
    private BigDecimal finalScore;
    private Integer isPass;
    private Integer rank;
    private String answerSheet;
    private String gradingDetail;
    private Integer abnormalCount;
    private Integer screenSwitchCount;
    private Integer disconnectCount;
    private Integer submitType;
    private String ipAddress;
    private String deviceInfo;
    private String abPaperType;
}
