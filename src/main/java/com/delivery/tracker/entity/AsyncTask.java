package com.delivery.tracker.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 异步任务实体
 */
@Data
@TableName("async_tasks")
public class AsyncTask {

    @TableId
    private String taskId;

    private String traceId;

    private String namespace;

    private String status;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime updatedAt;
}
