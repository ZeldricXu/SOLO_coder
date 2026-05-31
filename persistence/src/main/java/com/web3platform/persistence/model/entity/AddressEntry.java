package com.web3platform.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("address_entry")
public class AddressEntry {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("address")
    private String address;

    @TableField("chain_type")
    private String chainType;

    @TableField("label")
    private String label;

    @TableField("tags")
    private String tags;

    @TableField("note")
    private String note;

    @TableField("path")
    private String path;

    @TableField("hd_index")
    private Integer hdIndex;

    @TableField("public_key")
    private String publicKey;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
