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
@Table(name = "player_battle_stats", indexes = {
        @Index(name = "idx_pbs_battle_player", columnList = "battleId,playerId", unique = true),
        @Index(name = "idx_pbs_season", columnList = "seasonId,playerId")
})
public class PlayerBattleStat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String battleId;

    @Column(nullable = false)
    private Long seasonId;

    @Column(nullable = false)
    private Long playerId;

    @Column(nullable = false)
    private Integer serverId;

    @Column
    private Long guildId;

    @Column(nullable = false)
    private Integer kills;

    @Column(nullable = false)
    private Integer deaths;

    @Column(nullable = false)
    private Integer assists;

    @Column(nullable = false)
    private Integer captures;

    @Column(nullable = false)
    private Integer score;

    @Column(nullable = false)
    private Integer maxStreak;

    @Column(nullable = false)
    private Integer headshots;

    @Column
    private Double damageDealt;

    @Column
    private Double damageTaken;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
