package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_datasource")
public class Datasource extends BaseEntity {

    private String name;

    private String type;

    private String host;

    private Integer port;

    private String database;

    private String username;

    private String password;

    private String config;

    private String status;
}
