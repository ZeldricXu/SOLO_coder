package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quality_check_result")
public class QualityCheckResult extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String resultId;

    private String ruleId;

    private LocalDateTime checkTime;

    private String status;

    private Long totalCount;

    private Long errorCount;

    private String errorSample;

    private String errorDetail;
}
