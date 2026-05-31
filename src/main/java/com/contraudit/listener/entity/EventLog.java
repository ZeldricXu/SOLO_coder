package com.contraudit.listener.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("event_log")
public class EventLog extends BaseEntity {

    private String listenerId;

    private String chainType;

    private String contractAddress;

    private String eventName;

    private String txHash;

    private Long blockNumber;

    private String blockHash;

    private Integer logIndex;

    private String eventData;

    private String decodedData;

    private String status;

    private String callbackStatus;

    private String callbackResponse;

    private String errorMessage;

    private LocalDateTime processedAt;
}
