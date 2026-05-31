package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_table_schema")
public class TableSchema extends BaseEntity {

    private Long datasourceId;

    private String schemaName;

    private String tableName;

    private String tableComment;

    private Long rowCount;

    private Long sizeBytes;

    private String sampleData;

    private String crawlStatus;

    private LocalDateTime lastCrawlTime;
}
