package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("core_entity")
public class CoreEntityPO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String type;
    private String status;
    private String attributes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
