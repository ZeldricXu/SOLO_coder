package com.configcenter.common.entity;

import com.configcenter.common.enums.ConfigType;
import com.configcenter.common.enums.Environment;
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
@Table(name = "config_item", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"config_key", "environment", "group_id"})
})
public class ConfigItem {
    
    @Id
    @GenericGenerator(name = "uuid", strategy = "uuid")
    @GeneratedValue(generator = "uuid")
    @Column(name = "config_id", length = 36)
    private String configId;

    @Column(name = "config_key", nullable = false, length = 255)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT", nullable = false)
    private String configValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "config_type", length = 20, nullable = false)
    private ConfigType configType;

    @Column(name = "is_encrypted", nullable = false)
    private Boolean isEncrypted = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", length = 20, nullable = false)
    private Environment environment;

    @Column(name = "group_id", length = 36, nullable = false)
    private String groupId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "current_version", length = 20, nullable = false)
    private String currentVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
        if (currentVersion == null) {
            currentVersion = "v1";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
