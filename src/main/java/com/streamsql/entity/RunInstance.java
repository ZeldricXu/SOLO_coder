package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("run_instance")
public class RunInstance extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String runId;

    private String entityId;

    private String phase;

    private BigDecimal progress;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String errorDetail;
}
