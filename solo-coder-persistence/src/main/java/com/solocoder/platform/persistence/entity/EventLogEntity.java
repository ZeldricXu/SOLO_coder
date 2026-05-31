package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("event_log")
public class EventLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chain_id")
    private String chainId;

    @TableField("contract_address")
    private String contractAddress;

    @TableField("event_name")
    private String eventName;

    @TableField("event_signature")
    private String eventSignature;

    @TableField("topic0")
    private String topic0;

    @TableField("topic1")
    private String topic1;

    @TableField("topic2")
    private String topic2;

    @TableField("topic3")
    private String topic3;

    @TableField("data")
    private String data;

    @TableField("decoded_data")
    private String decodedData;

    @TableField("tx_hash")
    private String txHash;

    @TableField("block_number")
    private Long blockNumber;

    @TableField("block_hash")
    private String blockHash;

    @TableField("log_index")
    private Integer logIndex;

    @TableField("transaction_index")
    private Integer transactionIndex;

    @TableField("timestamp")
    private Long timestamp;

    @TableField("processed")
    private Integer processed;

    @TableField("processed_at")
    private LocalDateTime processedAt;

    @TableField("callback_status")
    private String callbackStatus;

    @TableField("callback_error")
    private String callbackError;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
