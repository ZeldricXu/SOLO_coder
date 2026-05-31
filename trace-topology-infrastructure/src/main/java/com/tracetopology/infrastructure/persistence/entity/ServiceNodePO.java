package com.tracetopology.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tracetopology.domain.topology.ServiceNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_node")
public class ServiceNodePO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String serviceName;
    private String serviceType;
    private String namespace;
    private String version;
    private String metadata;
    private Instant registeredAt;
    private Instant lastHeartbeatAt;
    private boolean active;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @SneakyThrows
    public static ServiceNodePO fromDomain(ServiceNode node) {
        return ServiceNodePO.builder()
                .id(node.getId())
                .serviceName(node.getServiceName())
                .serviceType(node.getServiceType())
                .namespace(node.getNamespace())
                .version(node.getVersion())
                .metadata(objectMapper.writeValueAsString(node.getMetadata()))
                .registeredAt(node.getRegisteredAt())
                .lastHeartbeatAt(node.getLastHeartbeatAt())
                .active(node.isActive())
                .build();
    }

    @SneakyThrows
    public ServiceNode toDomain() {
        return ServiceNode.builder()
                .id(this.id)
                .serviceName(this.serviceName)
                .serviceType(this.serviceType)
                .namespace(this.namespace)
                .version(this.version)
                .metadata(objectMapper.readValue(this.metadata, new TypeReference<Map<String, String>>() {}))
                .registeredAt(this.registeredAt)
                .lastHeartbeatAt(this.lastHeartbeatAt)
                .active(this.active)
                .build();
    }
}
