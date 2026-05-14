package com.configcenter.common.entity;

import com.configcenter.common.enums.InstanceStatus;
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
@Table(name = "application_instance", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"instance_address"})
}, indexes = {
        @Index(name = "idx_application", columnList = "application"),
        @Index(name = "idx_status", columnList = "status")
})
public class ApplicationInstance {
    
    @Id
    @GenericGenerator(name = "uuid", strategy = "uuid")
    @GeneratedValue(generator = "uuid")
    @Column(name = "instance_id", length = 36)
    private String instanceId;

    @Column(name = "application", length = 100, nullable = false)
    private String application;

    @Column(name = "instance_address", length = 100, nullable = false)
    private String instanceAddress;

    @Column(name = "last_config_sync")
    private LocalDateTime lastConfigSync;

    @Column(name = "config_version", length = 20)
    private String configVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private InstanceStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastHeartbeat == null) {
            lastHeartbeat = LocalDateTime.now();
        }
    }
}
