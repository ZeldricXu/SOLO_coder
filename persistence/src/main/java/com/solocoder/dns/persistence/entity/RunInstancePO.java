package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("run_instance")
public class RunInstancePO {
    @TableId(type = IdType.INPUT)
    private String runId;
    private String entityId;
    private String phase;
    private Double progress;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorDetail;
}
