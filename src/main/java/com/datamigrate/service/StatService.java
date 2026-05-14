package com.datamigrate.service;

import com.datamigrate.entity.MigrateStat;
import com.datamigrate.repository.MigrateStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatService {

    private final MigrateStatRepository statRepository;

    @Transactional
    public MigrateStat createStat(String taskId) {
        MigrateStat stat = new MigrateStat();
        stat.setStatId("stat_" + UUID.randomUUID().toString().substring(0, 8));
        stat.setTaskId(taskId);
        stat.setStartTime(LocalDateTime.now());
        return statRepository.save(stat);
    }

    @Transactional
    public void updateStatOnProgress(String taskId, long totalRecords, long successRecords, long failRecords) {
        statRepository.findByTaskId(taskId).ifPresent(stat -> {
            stat.setTotalRecords(totalRecords);
            stat.setSuccessRecords(successRecords);
            stat.setFailRecords(failRecords);
            
            if (stat.getStartTime() != null) {
                long seconds = Duration.between(stat.getStartTime(), LocalDateTime.now()).getSeconds();
                if (seconds > 0) {
                    double currentSpeed = (double) successRecords / seconds;
                    stat.setTotalDurationSeconds(seconds);
                    stat.setAvgSpeedPerSecond(currentSpeed);
                    if (currentSpeed > stat.getMaxSpeedPerSecond()) {
                        stat.setMaxSpeedPerSecond(currentSpeed);
                    }
                    if (stat.getMinSpeedPerSecond() == 0.0 || currentSpeed < stat.getMinSpeedPerSecond()) {
                        stat.setMinSpeedPerSecond(currentSpeed);
                    }
                }
            }
            statRepository.save(stat);
        });
    }

    @Transactional
    public void incrementBatch(String taskId) {
        statRepository.findByTaskId(taskId).ifPresent(stat -> {
            stat.setBatchCount(stat.getBatchCount() + 1);
            statRepository.save(stat);
        });
    }

    @Transactional
    public void incrementRetry(String taskId) {
        statRepository.findByTaskId(taskId).ifPresent(stat -> {
            stat.setRetryCount(stat.getRetryCount() + 1);
            statRepository.save(stat);
        });
    }

    @Transactional
    public void updateVerifyResult(String taskId, long totalVerified, long matchCount) {
        statRepository.findByTaskId(taskId).ifPresent(stat -> {
            if (totalVerified > 0) {
                double matchRate = (double) matchCount / totalVerified * 100;
                stat.setVerifyMatchRate(matchRate);
            }
            statRepository.save(stat);
        });
    }

    @Transactional
    public void completeStat(String taskId) {
        statRepository.findByTaskId(taskId).ifPresent(stat -> {
            LocalDateTime endTime = LocalDateTime.now();
            stat.setEndTime(endTime);
            
            if (stat.getStartTime() != null) {
                long seconds = Duration.between(stat.getStartTime(), endTime).getSeconds();
                stat.setTotalDurationSeconds(seconds);
                if (seconds > 0 && stat.getSuccessRecords() != null) {
                    stat.setAvgSpeedPerSecond((double) stat.getSuccessRecords() / seconds);
                }
            }
            statRepository.save(stat);
        });
    }

    public Optional<MigrateStat> getStatByTaskId(String taskId) {
        return statRepository.findByTaskId(taskId);
    }
}
