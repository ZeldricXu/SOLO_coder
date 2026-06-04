package com.battle.platform.repository;

import com.battle.platform.entity.PlayerBattleStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerBattleStatRepository extends JpaRepository<PlayerBattleStat, Long> {
    Optional<PlayerBattleStat> findByBattleIdAndPlayerId(String battleId, Long playerId);

    List<PlayerBattleStat> findBySeasonId(Long seasonId);

    List<PlayerBattleStat> findByPlayerId(Long playerId);

    List<PlayerBattleStat> findBySeasonIdAndPlayerId(Long seasonId, Long playerId);
}
