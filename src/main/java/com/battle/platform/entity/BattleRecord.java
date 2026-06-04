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
@Table(name = "battle_records", indexes = {
        @Index(name = "idx_battle_season", columnList = "seasonId"),
        @Index(name = "idx_battle_status", columnList = "status")
})
public class BattleRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long seasonId;

    @Column(nullable = false, unique = true)
    private String battleId;

    @Column(nullable = false)
    private Integer battleFieldId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private BattleStatus status;

    @Column(nullable = false)
    private Integer playerCount;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime endedAt;

    @Column
    private Integer durationSeconds;

    @Column
    private String replayFileKey;

    @Column
    private Boolean isAnomalous;

    @Column
    private String anomalyReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public enum BattleStatus {
        WAITING, IN_PROGRESS, FINISHED, CANCELLED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
