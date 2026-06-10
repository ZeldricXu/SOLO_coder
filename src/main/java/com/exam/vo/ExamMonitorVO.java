package com.exam.vo;

import lombok.Data;

import java.util.List;

@Data
public class ExamMonitorVO {
    private Long examId;
    private String examName;
    private Integer totalCandidates;
    private Integer onlineCount;
    private Integer submittedCount;
    private Integer unsubmittedCount;
    private Integer abnormalCount;
    private Integer seriousAbnormalCount;
    private Double submitProgress;
    private List<AbnormalAlertVO> recentAbnormals;
    private List<OnlineStudentVO> onlineStudents;
}
