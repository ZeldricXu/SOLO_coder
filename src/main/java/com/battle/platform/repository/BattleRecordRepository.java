package com.battle.platform.repository;

import com.battle.platform.entity.BattleRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BattleRecordRepository extends JpaRepository<BattleRecord, Long> {
    Optional<BattleRecord> findByBattleId(String battleId);

    List<BattleRecord> findBySeasonId(Long seasonId);

    List<BattleRecord> findByStatus(BattleRecord.BattleStatus status);

    List<BattleRecord> findByIsAnomalousTrue();
}
