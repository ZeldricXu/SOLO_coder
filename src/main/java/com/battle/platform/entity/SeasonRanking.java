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
@Table(name = "season_rankings", indexes = {
        @Index(name = "idx_rank_season_type_score", columnList = "seasonId,rankingType,score"),
        @Index(name = "idx_rank_season_player", columnList = "seasonId,playerId")
})
public class SeasonRanking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long seasonId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RankingType rankingType;

    @Column(nullable = false)
    private Long playerId;

    @Column
    private Long guildId;

    @Column(nullable = false)
    private Integer score;

    @Column(nullable = false)
    private Integer rank;

    @Column(nullable = false)
    private LocalDateTime snapshotAt;

    public enum RankingType {
        TOTAL_SCORE, KILLS, GUILD_SCORE
    }
}
