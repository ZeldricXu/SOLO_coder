package com.paycenter.entity;

import com.paycenter.enums.ChannelType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "channel_failover_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelFailoverLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String merchantId;

    @Column(nullable = false, length = 64)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChannelType channelType;

    @Column(nullable = false, length = 64)
    private String primaryChannelId;

    @Column(length = 64)
    private String backupChannelId;

    @Column(nullable = false)
    private Integer failureCount;

    @Column(nullable = false)
    private Boolean switched;

    @Column(length = 512)
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (failureCount == null) {
            failureCount = 0;
        }
        if (switched == null) {
            switched = false;
        }
    }
}
