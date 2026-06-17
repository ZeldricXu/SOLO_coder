package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_token_change")
public class TokenChange extends BaseEntity {
    private Long tokenId;
    private String changeType;
    private String oldName;
    private String newName;
    private String oldValue;
    private String newValue;
    private String migrationGuide;
    private String breakingChange;
    private String affectedComponents;
    private String affectedPages;
    private Long approvalRequestId;
    private LocalDateTime effectiveDate;
}
