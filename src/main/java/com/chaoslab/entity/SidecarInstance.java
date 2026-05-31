package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sidecar_instance")
public class SidecarInstance extends BaseEntity {

    private String instanceId;
    private String policyId;
    private String targetPod;
    private String namespace;
    private String status;
    private String configHash;
    private LocalDateTime lastHeartbeat;
    private Boolean configUpdatePending;
}
