package com.cardgame.replay.scheduler;

import com.cardgame.replay.config.BattleLogSamplingConfig;
import com.cardgame.replay.mapper.BattleLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
public class BattleLogCleanupScheduler {

    @Autowired
    private BattleLogMapper battleLogMapper;

    @Autowired
    private BattleLogSamplingConfig samplingConfig;

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldBattleLogs() {
        if (samplingConfig.getLogRetentionDays() <= 0) {
            log.info("Battle log retention is disabled, skipping cleanup");
            return;
        }

        long cutoffTimestamp = Instant.now()
                .minus(samplingConfig.getLogRetentionDays(), ChronoUnit.DAYS)
                .toEpochMilli();

        try {
            int deletedCount = battleLogMapper.deleteOldBattleLogs(cutoffTimestamp);
            log.info("Cleaned up {} battle logs older than {} days", deletedCount, samplingConfig.getLogRetentionDays());
        } catch (Exception e) {
            log.error("Failed to clean up old battle logs: {}", e.getMessage(), e);
        }
    }
}
