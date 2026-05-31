package com.datamasker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dm_mpc_session")
public class MpcSessionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String sessionId;

    private String protocolType;

    private Integer partyCount;

    private String status;

    private String resultEncrypted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
