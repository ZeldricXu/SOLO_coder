package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("event_listener_config")
public class EventListenerConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("listener_id")
    private String listenerId;

    @TableField("chain_id")
    private String chainId;

    @TableField("name")
    private String name;

    @TableField("contract_address")
    private String contractAddress;

    @TableField("contract_abi")
    private String contractAbi;

    @TableField("event_names")
    private String eventNames;

    @TableField("topic_filters")
    private String topicFilters;

    @TableField("start_block")
    private Long startBlock;

    @TableField("current_block")
    private Long currentBlock;

    @TableField("callback_url")
    private String callbackUrl;

    @TableField("callback_type")
    private String callbackType;

    @TableField("callback_headers")
    private String callbackHeaders;

    @TableField("retry_strategy")
    private String retryStrategy;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("retry_interval")
    private Integer retryInterval;

    @TableField("batch_size")
    private Integer batchSize;

    @TableField("is_enabled")
    private Integer isEnabled;

    @TableField("status")
    private String status;

    @TableField("last_error")
    private String lastError;

    @TableField("last_error_at")
    private LocalDateTime lastErrorAt;

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
