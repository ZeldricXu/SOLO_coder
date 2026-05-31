package com.cdcsync.lifecycle.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_lifecycle_policy")
public class LifecyclePolicy extends BaseEntity {

    private String name;

    private String resourceType;

    private Integer hotStorageDays;

    private Integer warmStorageDays;

    private Integer coldStorageDays;

    private Integer archiveAfterDays;

    private Integer deleteAfterDays;

    private Boolean enabled;

    private String configJson;
}
