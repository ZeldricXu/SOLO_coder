package com.chainetl.modules.storage.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.chainetl.common.handler.JsonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "storage_records", autoResultMap = true)
public class StorageRecord {

    @TableId(type = IdType.INPUT)
    private String recordId;

    private String storageType;

    private String contentHash;

    private String contentUrl;

    private String pinStatus;

    private Long size;

    private Instant createdAt;

    private Instant pinnedAt;

    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> metadata;
}
