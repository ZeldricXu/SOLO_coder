package com.cdcsync.metadata.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_table_info")
public class TableInfo extends BaseEntity {

    private String dataSourceId;
    private String schemaName;
    private String tableName;
    private Long rowCount;
    private Long sizeBytes;
    private String columnsJson;
    private String indexesJson;
    private String statisticsJson;
    private String sampleDataJson;
    private java.time.LocalDateTime lastAnalyzedAt;
}
