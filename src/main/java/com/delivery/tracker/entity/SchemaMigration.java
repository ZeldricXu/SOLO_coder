package com.delivery.tracker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.delivery.tracker.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("schema_migration")
public class SchemaMigration extends BaseEntity {

    private String version;

    private String scriptName;

    private String status;

    private String checksum;

    private LocalDateTime executedAt;
}
