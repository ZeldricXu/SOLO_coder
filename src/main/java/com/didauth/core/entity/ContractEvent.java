package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_contract_event")
public class ContractEvent extends BaseEntity {

    private String eventId;
    private String chainType;
    private String contractAddress;
    private String eventName;
    private String topic0;
    private String topic1;
    private String topic2;
    private String topic3;
    private String filterParams;
    private String callbackUrl;
    private String callbackType;
    private Boolean isActive;
    private String userId;
}
