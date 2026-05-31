package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("command_log")
public class CommandLog {

    private Long id;
    private String commandId;
    private String commandType;
    private String aggregateId;
    private Map<String, Object> payload;
    private Map<String, Object> metadata;
    private String status;
    private Map<String, Object> result;
    private String errorDetail;
    private LocalDateTime executedAt;
    private LocalDateTime completedAt;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
