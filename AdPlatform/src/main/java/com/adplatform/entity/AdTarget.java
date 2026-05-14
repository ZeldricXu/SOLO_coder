package com.adplatform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ad_target")
public class AdTarget {
    @Id
    @Column(name = "target_id", length = 50)
    private String targetId;

    @Column(name = "ad_id", length = 50, nullable = false)
    private String adId;

    @Column(name = "target_type", length = 100, nullable = false)
    private String targetType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_conditions", columnDefinition = "JSON", nullable = false)
    private Map<String, Object> targetConditions;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
