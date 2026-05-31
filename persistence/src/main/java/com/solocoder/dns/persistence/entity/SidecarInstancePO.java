package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sidecar_instance")
public class SidecarInstancePO {
    @TableId(type = IdType.INPUT)
    private String instanceId;
    private String serviceName;
    private String version;
    private String host;
    private Integer port;
    private String status;
    private String configHash;
    private Double cpuLimit;
    private Double memoryLimit;
    private LocalDateTime createdAt;
    private LocalDateTime heartbeatAt;
}
