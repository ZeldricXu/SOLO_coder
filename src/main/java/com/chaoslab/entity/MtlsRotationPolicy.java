package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mtls_rotation_policy")
public class MtlsRotationPolicy extends BaseEntity {

    private String policyId;
    private String name;
    private Integer validityDays;
    private Integer rotationDays;
    private Boolean autoRotate;
    private String keyAlgorithm;
    private Integer keySize;
    private String signatureAlgorithm;
    private Boolean enabled;
}
