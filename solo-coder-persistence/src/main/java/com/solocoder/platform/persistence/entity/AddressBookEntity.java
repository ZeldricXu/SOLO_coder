package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("address_book")
public class AddressBookEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("entry_id")
    private String entryId;

    @TableField("chain_id")
    private String chainId;

    @TableField("address")
    private String address;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("labels")
    private String labels;

    @TableField("address_type")
    private String addressType;

    @TableField("verified")
    private Integer verified;

    @TableField("risk_level")
    private String riskLevel;

    @TableField("metadata")
    private String metadata;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
