package com.battle.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "reward_records",
        indexes = {
                @Index(name = "idx_reward_season_player", columnList = "seasonId,playerId"),
                @Index(name = "idx_reward_status", columnList = "status"),
                @Index(name = "idx_reward_request_id", columnList = "requestId", unique = true)
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_player_season_type", columnNames = {"playerId", "seasonId", "rewardType"})
        })
public class RewardRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long seasonId;

    @Column(nullable = false)
    private Long playerId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RewardType rewardType;

    @Column(nullable = false)
    private Integer rank;

    @Column(nullable = false)
    private String rewardContentJson;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RewardStatus status;

    @Column
    private Integer retryCount;

    @Column
    private String failReason;

    @Column
    private Long guildId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime deliveredAt;

    @Column(unique = true)
    private String requestId;

    public enum RewardType {
        PERSONAL, GUILD
    }

    public enum RewardStatus {
        PENDING, DELIVERED, FAILED, CANCELLED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
