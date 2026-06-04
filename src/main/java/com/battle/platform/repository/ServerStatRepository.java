package com.battle.platform.repository;

import com.battle.platform.entity.ServerStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerStatRepository extends JpaRepository<ServerStat, Long> {
    Optional<ServerStat> findByServerId(Integer serverId);

    List<ServerStat> findByServerPowerScoreBetween(Double min, Double max);

    List<ServerStat> findAllByOrderByServerPowerScoreDesc();
}
