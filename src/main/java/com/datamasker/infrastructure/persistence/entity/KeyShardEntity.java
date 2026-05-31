package com.datamasker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dm_key_shard")
public class KeyShardEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String secretId;

    private Integer shardIndex;

    private String shardData;

    private Integer threshold;

    private Integer totalShares;

    private String owner;

    private LocalDateTime createdAt;
}
