package com.contraudit.bridge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bridge_message")
public class BridgeMessage extends BaseEntity {

    private String messageId;

    private Long fromChainId;

    private Long toChainId;

    private String messageType;

    private String payload;

    private String signature;

    private String status;

    private Long nonce;

    private LocalDateTime verifiedAt;

    private LocalDateTime deliveredAt;
}
