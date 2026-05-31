package com.datastandard.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "profiling_sessions", autoResultMap = true)
public class ProfilingSession {

    @TableId(type = IdType.INPUT)
    @TableField("session_id")
    private String sessionId;

    @TableField("session_type")
    private String sessionType;

    @TableField("target_pid")
    private Integer targetPid;

    @TableField("duration_seconds")
    private Integer durationSeconds;

    @TableField("sampling_rate")
    private Integer samplingRate;

    @TableField("status")
    private String status;

    @TableField("output_path")
    private String outputPath;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("ended_at")
    private LocalDateTime endedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
