package com.battle.platform.repository;

import com.battle.platform.entity.RewardRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RewardRecordRepository extends JpaRepository<RewardRecord, Long> {
    List<RewardRecord> findBySeasonIdAndPlayerId(Long seasonId, Long playerId);

    List<RewardRecord> findByStatus(RewardRecord.RewardStatus status);

    List<RewardRecord> findByStatusAndRetryCountLessThan(RewardRecord.RewardStatus status, int maxRetry);
}
