package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_block_index")
public class BlockIndex extends BaseEntity {

    private String chainType;
    private Long blockNumber;
    private String blockHash;
    private String parentHash;
    private String miner;
    private Long timestamp;
    private Integer transactionCount;
    private String gasUsed;
    private String gasLimit;
    private String extraData;
    private String status;
}
