package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_info")
public class Exam extends BaseEntity {
    private String examName;
    private String examCode;
    private Long subjectId;
    private Long paperId;
    private Integer paperMode;
    private Integer totalScore;
    private Integer passScore;
    private Integer duration;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enterStartTime;
    private LocalDateTime enterEndTime;
    private Integer examStatus;
    private Integer allowSwitchScreen;
    private Integer maxSwitchScreenCount;
    private Integer allowBack;
    private Integer randomOrder;
    private Integer abPaper;
    private String description;
    private Long classId;
    private String candidateIds;
    private Integer totalCandidates;
    private Integer submittedCount;
    private Integer gradingStatus;
    private String rules;
    private String notice;
}
