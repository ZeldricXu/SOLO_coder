package com.contraudit.listener.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("event_listener")
public class EventListener extends BaseEntity {

    private String listenerName;

    private String chainType;

    private String contractAddress;

    private String eventName;

    private String eventSignature;

    private String abiDefinition;

    private Long startBlock;

    private Long currentBlock;

    private String callbackType;

    private String callbackUrl;

    private String callbackMethod;

    private String callbackHeaders;

    private Integer retryCount;

    private Integer retryInterval;

    private String filterParams;

    private Integer status;
}
