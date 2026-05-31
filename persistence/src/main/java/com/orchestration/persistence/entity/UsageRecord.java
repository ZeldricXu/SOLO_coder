package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usage_record")
public class UsageRecord extends TenantEntity {

    private String resourceType;

    private Long usageAmount;

    private String unit;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String tagsJson;
}
