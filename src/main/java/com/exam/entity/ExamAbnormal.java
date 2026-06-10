package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_exam_abnormal")
public class ExamAbnormal extends BaseEntity {
    private Long examId;
    private Long sessionId;
    private Long studentId;
    private Integer abnormalType;
    private String abnormalName;
    private String description;
    private LocalDateTime happenTime;
    private String clientIp;
    private String extraInfo;
    private Integer handled;
    private String handleRemark;
    private Long handleBy;
    private LocalDateTime handleTime;
}
