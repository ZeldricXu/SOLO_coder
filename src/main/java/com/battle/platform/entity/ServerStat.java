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
@Table(name = "server_stats", indexes = {
        @Index(name = "idx_server_stat_id", columnList = "serverId")
})
public class ServerStat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Integer serverId;

    @Column(nullable = false)
    private String serverName;

    @Column(nullable = false)
    private Double serverPowerScore;

    @Column(nullable = false)
    private Integer avgPlayerCombatPower;

    @Column(nullable = false)
    private Integer totalActivePlayers;

    @Column(nullable = false)
    private LocalDateTime openedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
