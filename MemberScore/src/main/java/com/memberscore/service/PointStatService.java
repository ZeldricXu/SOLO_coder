package com.memberscore.service;

import com.memberscore.entity.PointStat;
import com.memberscore.enums.PointType;
import com.memberscore.repository.PointRecordRepository;
import com.memberscore.repository.PointStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PointStatService {
    
    private final PointStatRepository pointStatRepository;
    private final PointRecordRepository pointRecordRepository;
    
    @Transactional
    public void recordEarnStat(int points) {
        updateDailyStat(LocalDate.now(), points, 0, 1, 0);
    }
    
    @Transactional
    public void recordConsumeStat(int points) {
        updateDailyStat(LocalDate.now(), 0, points, 0, 1);
    }
    
    @Transactional
    public void updateDailyStat(LocalDate date, int earnPoints, int consumePoints, 
                                int earnCountIncrement, int consumeCountIncrement) {
        PointStat stat = pointStatRepository.findByStatDate(date).orElse(null);
        
        if (stat == null) {
            stat = PointStat.builder()
                    .statId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                    .statDate(date)
                    .earnCount(earnCountIncrement)
                    .earnPoints(earnPoints)
                    .consumeCount(consumeCountIncrement)
                    .consumePoints(consumePoints)
                    .build();
        } else {
            stat.setEarnCount(stat.getEarnCount() + earnCountIncrement);
            stat.setEarnPoints(stat.getEarnPoints() + earnPoints);
            stat.setConsumeCount(stat.getConsumeCount() + consumeCountIncrement);
            stat.setConsumePoints(stat.getConsumePoints() + consumePoints);
        }
        
        pointStatRepository.save(stat);
    }
    
    @Transactional
    public void aggregateDailyStats(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
        
        Integer earnPoints = pointRecordRepository.sumPointsByDateRangeAndType(
                startOfDay, endOfDay, PointType.EARN);
        Long earnCount = pointRecordRepository.countPointsByDateRangeAndType(
                startOfDay, endOfDay, PointType.EARN);
        
        Integer consumePoints = pointRecordRepository.sumPointsByDateRangeAndType(
                startOfDay, endOfDay, PointType.CONSUME);
        Long consumeCount = pointRecordRepository.countPointsByDateRangeAndType(
                startOfDay, endOfDay, PointType.CONSUME);
        
        PointStat stat = pointStatRepository.findByStatDate(date).orElse(null);
        if (stat == null) {
            stat = PointStat.builder()
                    .statId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                    .statDate(date)
                    .build();
        }
        
        stat.setEarnPoints(earnPoints != null ? earnPoints : 0);
        stat.setEarnCount(earnCount != null ? earnCount.intValue() : 0);
        stat.setConsumePoints(consumePoints != null ? consumePoints : 0);
        stat.setConsumeCount(consumeCount != null ? consumeCount.intValue() : 0);
        
        pointStatRepository.save(stat);
        log.info("聚合每日统计: date={}, earnPoints={}, consumePoints={}", 
                date, stat.getEarnPoints(), stat.getConsumePoints());
    }
    
    @Transactional(readOnly = true)
    public Integer getTotalEarnPoints(LocalDate start, LocalDate end) {
        return pointStatRepository.sumEarnPointsBetween(start, end);
    }
    
    @Transactional(readOnly = true)
    public Integer getTotalConsumePoints(LocalDate start, LocalDate end) {
        return pointStatRepository.sumConsumePointsBetween(start, end);
    }
    
    @Transactional(readOnly = true)
    public Long getTotalEarnCount(LocalDate start, LocalDate end) {
        return pointStatRepository.sumEarnCountBetween(start, end);
    }
    
    @Transactional(readOnly = true)
    public Long getTotalConsumeCount(LocalDate start, LocalDate end) {
        return pointStatRepository.sumConsumeCountBetween(start, end);
    }
}
