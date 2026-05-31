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
@TableName("datasource_info")
public class DatasourceInfo extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String datasourceId;

    private String datasourceName;

    private String datasourceType;

    private String connectionConfig;

    private String status;

    private LocalDateTime lastCrawlTime;
}
