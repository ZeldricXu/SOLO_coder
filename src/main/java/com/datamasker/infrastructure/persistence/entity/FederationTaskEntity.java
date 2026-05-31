package com.datamasker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dm_federation_task")
public class FederationTaskEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String taskId;

    private Integer roundNumber;

    private Integer participantCount;

    private String status;

    private String globalModelHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
