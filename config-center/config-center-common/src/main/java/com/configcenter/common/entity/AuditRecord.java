package com.configcenter.common.entity;

import com.configcenter.common.enums.AuditOperation;
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
@Table(name = "audit_record", indexes = {
        @Index(name = "idx_config_id", columnList = "config_id"),
        @Index(name = "idx_operator", columnList = "operator"),
        @Index(name = "idx_operated_at", columnList = "operated_at")
})
public class AuditRecord {
    
    @Id
    @GenericGenerator(name = "uuid", strategy = "uuid")
    @GeneratedValue(generator = "uuid")
    @Column(name = "audit_id", length = 36)
    private String auditId;

    @Column(name = "config_id", length = 36, nullable = false)
    private String configId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", length = 20, nullable = false)
    private AuditOperation operation;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "operator", length = 100, nullable = false)
    private String operator;

    @Column(name = "operated_at", nullable = false)
    private LocalDateTime operatedAt;

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @PrePersist
    public void prePersist() {
        if (operatedAt == null) {
            operatedAt = LocalDateTime.now();
        }
    }
}
