package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_abnormal_record")
public class AbnormalRecord extends BaseEntity {
    private Long examId;
    private Long examRecordId;
    private Long userId;
    private Integer abnormalType;
    private String abnormalDetail;
    private LocalDateTime abnormalTime;
    private String ipAddress;
    private String deviceInfo;
    private Integer severity;
    private Integer handled;
    private String handleRemark;
    private Long handleBy;
    private LocalDateTime handleTime;
}
