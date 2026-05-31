package com.cdcsync.metadata.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_schema_info")
public class SchemaInfo extends BaseEntity {

    private String dataSourceId;
    private String schemaName;
    private Integer tableCount;
    private String metadataJson;
    private java.time.LocalDateTime lastCrawledAt;
}
