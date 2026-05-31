package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dns_resolution_policy")
public class DnsResolutionPolicy extends BaseEntity {

    private String policyId;
    private String name;
    private String domainPattern;
    private String strategy;
    private List<String> upstreamIds;
    private Integer cacheTtl;
    private Boolean enabled;
}
