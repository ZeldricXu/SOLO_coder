package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("lifecycle_policy")
public class LifecyclePolicy extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String policyId;

    private String policyName;

    private String datasourceId;

    private String tableName;

    private Integer hotStorageDays;

    private Integer coldStorageDays;

    private Integer archiveStorageDays;

    private Boolean enabled;

    private LocalDateTime lastMigrateTime;
}
