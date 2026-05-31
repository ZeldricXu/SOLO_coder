package com.tracetopology.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tracetopology.domain.entity.Entity;
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
@TableName("t_entity")
public class EntityPO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String type;
    private String status;
    private String attributes;
    private Instant createdAt;
    private Instant updatedAt;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @SneakyThrows
    public static EntityPO fromDomain(Entity entity) {
        return EntityPO.builder()
                .id(entity.getId())
                .type(entity.getType())
                .status(entity.getStatus())
                .attributes(objectMapper.writeValueAsString(entity.getAttributes()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    @SneakyThrows
    public Entity toDomain() {
        return Entity.builder()
                .id(this.id)
                .type(this.type)
                .status(this.status)
                .attributes(objectMapper.readValue(this.attributes, new TypeReference<Map<String, Object>>() {}))
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }
}
