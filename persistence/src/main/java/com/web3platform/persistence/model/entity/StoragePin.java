package com.web3platform.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("storage_pin")
public class StoragePin {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("cid")
    private String cid;

    @TableField("storage_type")
    private String storageType;

    @TableField("pin_status")
    private String pinStatus;

    @TableField("pinned_at")
    private LocalDateTime pinnedAt;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
