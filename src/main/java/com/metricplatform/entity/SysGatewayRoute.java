package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_gateway_route")
public class SysGatewayRoute extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String routeId;

    private String path;

    private String targetUrl;

    private Boolean authRequired;

    private Boolean rateLimitEnabled;

    private Integer rateLimitCapacity;

    private Integer rateLimitRefill;

    private Boolean enabled;
}
