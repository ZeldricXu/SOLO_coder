package com.cdcsync.metadata.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_data_source")
public class DataSource extends BaseEntity {

    private String name;
    private String type;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String password;
    private String configJson;
    private String status;
}
