package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sidecar_injection_policy")
public class SidecarInjectionPolicy extends BaseEntity {

    private String policyId;
    private String name;
    private String namespace;
    private Map<String, Object> selector;
    private String sidecarImage;
    private Map<String, Object> resources;
    private String injectionMode;
    private Boolean enabled;
}
