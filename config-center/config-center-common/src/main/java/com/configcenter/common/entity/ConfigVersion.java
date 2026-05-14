package com.configcenter.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "config_version", indexes = {
        @Index(name = "idx_config_id", columnList = "config_id"),
        @Index(name = "idx_config_version", columnList = {"config_id", "version"})
})
public class ConfigVersion {
    
    @Id
    @GenericGenerator(name = "uuid", strategy = "uuid")
    @GeneratedValue(generator = "uuid")
    @Column(name = "version_id", length = 36)
    private String versionId;

    @Column(name = "config_id", length = 36, nullable = false)
    private String configId;

    @Column(name = "version", length = 20, nullable = false)
    private String version;

    @Column(name = "config_value", columnDefinition = "TEXT", nullable = false)
    private String configValue;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Column(name = "changed_by", length = 100)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "is_rollback", nullable = false)
    private Boolean isRollback = false;

    @Column(name = "rollback_from_version", length = 20)
    private String rollbackFromVersion;

    @PrePersist
    public void prePersist() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}
