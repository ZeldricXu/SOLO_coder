package com.battle.platform.repository;

import com.battle.platform.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByPlayerId(Long playerId);

    List<Player> findByServerId(Integer serverId);

    List<Player> findByGuildId(Long guildId);

    List<Player> findByServerIdAndIsBannedFalse(Integer serverId);

    List<Player> findByRatingBetween(Double minRating, Double maxRating);
}
