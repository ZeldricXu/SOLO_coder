package com.exam.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AbnormalAlertVO {
    private Long id;
    private Long examId;
    private Long examRecordId;
    private Long userId;
    private String userName;
    private Integer abnormalType;
    private String abnormalTypeText;
    private String abnormalDetail;
    private LocalDateTime abnormalTime;
    private Integer severity;
    private String severityText;
    private Integer handled;
    private String handleRemark;
    private String handleBy;
    private LocalDateTime handleTime;
}
