package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_transaction_index")
public class TransactionIndex extends BaseEntity {

    private String chainType;
    private Long blockNumber;
    private String txHash;
    private Integer txIndex;
    private String fromAddress;
    private String toAddress;
    private String value;
    private String gasPrice;
    private String gasLimit;
    private String gasUsed;
    private String inputData;
    private String status;
    private String contractAddress;
    private Long timestamp;
}
