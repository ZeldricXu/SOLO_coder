package com.exam.vo;

import lombok.Data;

@Data
public class OnlineStudentVO {
    private Long userId;
    private String userName;
    private String realName;
    private Integer answerProgress;
    private Integer answeredCount;
    private Integer totalQuestions;
    private Long lastHeartbeat;
    private Integer abnormalCount;
}
