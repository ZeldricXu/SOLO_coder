package com.configcenter.common.entity;

import com.configcenter.common.enums.PushStatus;
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
@Table(name = "push_record", indexes = {
        @Index(name = "idx_config_id", columnList = "config_id"),
        @Index(name = "idx_target_group", columnList = "target_group")
})
public class PushRecord {
    
    @Id
    @GenericGenerator(name = "uuid", strategy = "uuid")
    @GeneratedValue(generator = "uuid")
    @Column(name = "push_id", length = 36)
    private String pushId;

    @Column(name = "config_id", length = 36, nullable = false)
    private String configId;

    @Column(name = "version", length = 20, nullable = false)
    private String version;

    @Column(name = "target_group", length = 36, nullable = false)
    private String targetGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_status", length = 20, nullable = false)
    private PushStatus pushStatus;

    @Column(name = "push_time")
    private LocalDateTime pushTime;

    @Column(name = "complete_time")
    private LocalDateTime completeTime;

    @Column(name = "success_count", nullable = false)
    private Integer successCount = 0;

    @Column(name = "fail_count", nullable = false)
    private Integer failCount = 0;

    @Column(name = "total_count", nullable = false)
    private Integer totalCount = 0;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "push_by", length = 100)
    private String pushBy;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    public void prePersist() {
        if (pushTime == null) {
            pushTime = LocalDateTime.now();
        }
    }
}
