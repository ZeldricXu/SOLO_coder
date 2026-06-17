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
import java.util.Map;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "model_inference_logs", indexes = {
        @Index(name = "idx_inference_model_time", columnList = "model_id, inference_time"),
        @Index(name = "idx_inference_entity", columnList = "entity_id, entity_type"),
        @Index(name = "idx_inference_event_id", columnList = "event_id"),
        @Index(name = "idx_inference_time", columnList = "inference_time")
})
public class ModelInferenceLogEntity implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "model_id", length = 64, nullable = false)
    private String modelId;

    @Column(name = "model_name", length = 256, nullable = false)
    private String modelName;

    @Column(name = "model_version", length = 64, nullable = false)
    private String modelVersion;

    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "entity_id", length = 128)
    private String entityId;

    @Column(name = "entity_type", length = 64)
    private String entityType;

    @Column(name = "business_line", length = 64)
    private String businessLine;

    @Column(name = "inference_time", nullable = false)
    @Builder.Default
    private Long inferenceTime = Instant.now().toEpochMilli();

    @Column(name = "prediction_score")
    private Double predictionScore;

    @Column(name = "threshold")
    private Double threshold;

    @Column(name = "anomaly_detected", nullable = false)
    @Builder.Default
    private Boolean anomalyDetected = false;

    @Column(name = "inference_latency_ms")
    private Long inferenceLatencyMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_features", columnDefinition = "jsonb")
    private Map<String, Object> inputFeatures;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_output", columnDefinition = "jsonb")
    private Map<String, Object> rawOutput;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "success", nullable = false)
    @Builder.Default
    private Boolean success = true;
}
