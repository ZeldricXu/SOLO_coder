package com.chainetl.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("run_instances")
public class RunInstance {

    @TableId(type = IdType.INPUT)
    private String runId;

    private String entityId;

    private String phase;

    private Double progress;

    private Instant startedAt;

    private Instant completedAt;

    private String errorDetail;
}
