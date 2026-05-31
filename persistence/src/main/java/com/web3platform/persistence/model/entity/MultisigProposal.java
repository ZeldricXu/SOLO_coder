package com.web3platform.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("multisig_proposal")
public class MultisigProposal {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("wallet_address")
    private String walletAddress;

    @TableField("proposal_type")
    private String proposalType;

    @TableField("target_address")
    private String targetAddress;

    @TableField("value")
    private BigDecimal value;

    @TableField("data")
    private String data;

    @TableField("status")
    private String status;

    @TableField("threshold")
    private Integer threshold;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
