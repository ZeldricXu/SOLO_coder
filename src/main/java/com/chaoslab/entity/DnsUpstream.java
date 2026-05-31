package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dns_upstream")
public class DnsUpstream extends BaseEntity {

    private String upstreamId;
    private String name;
    private String address;
    private String protocol;
    private Integer timeoutMs;
    private Integer priority;
    private Boolean healthCheckEnabled;
    private String status;
}
