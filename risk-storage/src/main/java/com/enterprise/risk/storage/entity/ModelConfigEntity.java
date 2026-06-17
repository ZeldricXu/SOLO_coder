package com.enterprise.risk.storage.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "model_configs", indexes = {
        @Index(name = "idx_model_enabled", columnList = "enabled"),
        @Index(name = "idx_model_name_version", columnList = "model_name, model_version", unique = true)
})
public class ModelConfigEntity implements Serializable {

    @Id
    @Column(name = "model_id", length = 64, nullable = false)
    private String modelId;

    @Column(name = "model_name", length = 256, nullable = false)
    private String modelName;

    @Column(name = "model_version", length = 64, nullable = false)
    private String modelVersion;

    @Column(name = "model_path", length = 512, nullable = false)
    private String modelPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_names", columnDefinition = "jsonb")
    private List<String> featureNames;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_extractors", columnDefinition = "jsonb")
    private Map<String, String> featureExtractors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_values", columnDefinition = "jsonb")
    private Map<String, Object> defaultValues;

    @Column(name = "input_name", length = 128)
    private String inputName;

    @Column(name = "output_name", length = 128)
    private String outputName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_shape", columnDefinition = "jsonb")
    private long[] outputShape;

    @Column(name = "threshold", nullable = false)
    @Builder.Default
    private Double threshold = 0.5;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Long createdAt = Instant.now().toEpochMilli();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Long updatedAt = Instant.now().toEpochMilli();

    @Column(name = "weight", nullable = false)
    @Builder.Default
    private Double weight = 0.5;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now().toEpochMilli();
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
