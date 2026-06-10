package com.exam.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RealtimeExamVO {
    private Long examId;
    private String examName;
    private Long subjectId;
    private String subjectName;
    private Integer examStatus;
    private String examStatusText;
    private Integer totalCandidates;
    private Integer onlineCount;
    private Integer submittedCount;
    private Integer abnormalCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
