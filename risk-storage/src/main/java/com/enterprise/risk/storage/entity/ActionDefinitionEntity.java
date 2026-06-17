package com.enterprise.risk.storage.entity;

import com.enterprise.risk.common.orchestration.ActionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "action_definitions", indexes = {
        @Index(name = "idx_action_type", columnList = "action_type"),
        @Index(name = "idx_action_enabled", columnList = "enabled"),
        @Index(name = "idx_action_business", columnList = "business_line")
})
public class ActionDefinitionEntity implements Serializable {

    @Id
    @Column(name = "action_id", length = 64, nullable = false)
    private String actionId;

    @Column(name = "action_name", length = 256, nullable = false)
    private String actionName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 64, nullable = false)
    private ActionType actionType;

    @Column(name = "business_line", length = 64)
    private String businessLine;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_params", columnDefinition = "jsonb")
    private Map<String, Object> actionParams;

    @Column(name = "timeout_ms")
    @Builder.Default
    private Long timeoutMs = 30000L;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 3;

    @Column(name = "retry_interval_ms")
    @Builder.Default
    private Long retryIntervalMs = 1000L;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "async_execution", nullable = false)
    @Builder.Default
    private Boolean asyncExecution = true;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Long createdAt = Instant.now().toEpochMilli();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Long updatedAt = Instant.now().toEpochMilli();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now().toEpochMilli();
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isAsyncExecution() {
        return Boolean.TRUE.equals(asyncExecution);
    }
}
