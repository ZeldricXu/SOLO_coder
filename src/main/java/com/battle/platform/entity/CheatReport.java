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
@Table(name = "cheat_reports", indexes = {
        @Index(name = "idx_cheat_status", columnList = "status"),
        @Index(name = "idx_cheat_player", columnList = "playerId")
})
public class CheatReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long playerId;

    @Column(nullable = false)
    private String battleId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CheatType cheatType;

    @Column(nullable = false)
    private Double confidence;

    @Column(length = 2000)
    private String detail;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ReportStatus status;

    @Column
    private String reviewer;

    @Column
    private String reviewNote;

    @Column(nullable = false)
    private LocalDateTime detectedAt;

    @Column
    private LocalDateTime reviewedAt;

    public enum CheatType {
        SPEED_HACK, AIMBOT, MULTI_ACCOUNT, ABNORMAL_TRAJECTORY, OTHER
    }

    public enum ReportStatus {
        PENDING, CONFIRMED, FALSE_POSITIVE, UNDER_REVIEW
    }

    @PrePersist
    protected void onCreate() {
        detectedAt = LocalDateTime.now();
    }
}
