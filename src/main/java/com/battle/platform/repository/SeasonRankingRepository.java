package com.battle.platform.repository;

import com.battle.platform.entity.SeasonRanking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SeasonRankingRepository extends JpaRepository<SeasonRanking, Long> {
    List<SeasonRanking> findBySeasonIdAndRankingTypeOrderByRankAsc(Long seasonId, SeasonRanking.RankingType type);

    List<SeasonRanking> findBySeasonIdAndPlayerId(Long seasonId, Long playerId);
}
