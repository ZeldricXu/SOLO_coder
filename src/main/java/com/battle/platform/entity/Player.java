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
@Table(name = "players", indexes = {
        @Index(name = "idx_player_server", columnList = "serverId"),
        @Index(name = "idx_player_guild", columnList = "guildId")
})
public class Player {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long playerId;

    @Column(nullable = false)
    private Integer serverId;

    @Column(nullable = false)
    private String playerName;

    @Column(nullable = false)
    private Long combatPower;

    @Column
    private Long guildId;

    @Column
    private String guildName;

    @Column(nullable = false)
    private Double rating;

    @Column(nullable = false)
    private Integer totalKills;

    @Column(nullable = false)
    private Integer totalDeaths;

    @Column(nullable = false)
    private Integer totalAssists;

    @Column(nullable = false)
    private Integer totalScore;

    @Column
    private Integer headshots;

    @Column(nullable = false)
    private Boolean isBanned;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
