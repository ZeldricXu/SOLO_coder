package com.tracetopology.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tracetopology.domain.entity.Config;
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
@TableName("t_config")
public class ConfigPO {

    @TableId(type = IdType.INPUT)
    private String configId;
    private String namespace;
    private int version;
    private String parameters;
    private boolean enabled;
    private Instant appliedAt;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @SneakyThrows
    public static ConfigPO fromDomain(Config config) {
        return ConfigPO.builder()
                .configId(config.getConfigId())
                .namespace(config.getNamespace())
                .version(config.getVersion())
                .parameters(objectMapper.writeValueAsString(config.getParameters()))
                .enabled(config.isEnabled())
                .appliedAt(config.getAppliedAt())
                .build();
    }

    @SneakyThrows
    public Config toDomain() {
        return Config.builder()
                .configId(this.configId)
                .namespace(this.namespace)
                .version(this.version)
                .parameters(objectMapper.readValue(this.parameters, new TypeReference<Map<String, Object>>() {}))
                .enabled(this.enabled)
                .appliedAt(this.appliedAt)
                .build();
    }
}
