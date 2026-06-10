package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_exam")
public class Exam extends BaseEntity {
    private String examName;
    private Long subjectId;
    private Long paperId;
    private String examCode;
    private Integer examStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Integer allowLateEntry;
    private Integer lateEntryMinutes;
    private Integer allowLateSubmit;
    private Integer lateSubmitMinutes;
    private Integer antiCheatingLevel;
    private Integer maxScreenSwitch;
    private Integer autoSubmitOnTimeOut;
    private String classIds;
    private String studentIds;
    private String description;
    private Long createBy;
}
