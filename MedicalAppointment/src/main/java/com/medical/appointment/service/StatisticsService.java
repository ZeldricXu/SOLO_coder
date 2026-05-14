package com.medical.appointment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medical.appointment.entity.Statistics;
import com.medical.appointment.repository.StatisticsRepository;
import com.medical.appointment.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class StatisticsService {
    
    private final StatisticsRepository statisticsRepository;
    private final ObjectMapper objectMapper;
    
    public StatisticsService(StatisticsRepository statisticsRepository, ObjectMapper objectMapper) {
        this.statisticsRepository = statisticsRepository;
        this.objectMapper = objectMapper;
    }
    
    private Statistics getOrCreateCurrentMonthStats() {
        String currentMonth = IdGenerator.getCurrentMonth();
        Optional<Statistics> statsOpt = statisticsRepository.findByStatMonth(currentMonth);
        
        if (statsOpt.isPresent()) {
            Statistics stats = statsOpt.get();
            deserializeDepartmentStat(stats);
            return stats;
        }
        
        Statistics newStats = new Statistics();
        newStats.setStatId(IdGenerator.generateStatisticsId());
        newStats.setStatMonth(currentMonth);
        newStats.setAppointmentCount(0);
        newStats.setVisitCount(0);
        newStats.setCancelCount(0);
        newStats.setDepartmentStat(new HashMap<>());
        serializeDepartmentStat(newStats);
        return statisticsRepository.save(newStats);
    }
    
    public void incrementAppointmentCount(String departmentId) {
        Statistics stats = getOrCreateCurrentMonthStats();
        stats.setAppointmentCount(stats.getAppointmentCount() + 1);
        
        Map<String, Integer> deptStat = stats.getDepartmentStat();
        deptStat.put(departmentId, deptStat.getOrDefault(departmentId, 0) + 1);
        
        serializeDepartmentStat(stats);
        statisticsRepository.save(stats);
    }
    
    public void incrementVisitCount() {
        Statistics stats = getOrCreateCurrentMonthStats();
        stats.setVisitCount(stats.getVisitCount() + 1);
        serializeDepartmentStat(stats);
        statisticsRepository.save(stats);
    }
    
    public void incrementCancelCount() {
        Statistics stats = getOrCreateCurrentMonthStats();
        stats.setCancelCount(stats.getCancelCount() + 1);
        serializeDepartmentStat(stats);
        statisticsRepository.save(stats);
    }
    
    public Statistics getCurrentMonthStatistics() {
        String currentMonth = IdGenerator.getCurrentMonth();
        return statisticsRepository.findByStatMonth(currentMonth)
                .map(stats -> {
                    deserializeDepartmentStat(stats);
                    return stats;
                })
                .orElse(null);
    }
    
    public Statistics getStatisticsByMonth(String month) {
        return statisticsRepository.findByStatMonth(month)
                .map(stats -> {
                    deserializeDepartmentStat(stats);
                    return stats;
                })
                .orElse(null);
    }
    
    private void serializeDepartmentStat(Statistics stats) {
        try {
            if (stats.getDepartmentStat() != null) {
                stats.setDepartmentStatJson(objectMapper.writeValueAsString(stats.getDepartmentStat()));
            }
        } catch (Exception e) {
            stats.setDepartmentStatJson("{}");
        }
    }
    
    private void deserializeDepartmentStat(Statistics stats) {
        try {
            if (stats.getDepartmentStatJson() != null) {
                stats.setDepartmentStat(objectMapper.readValue(
                        stats.getDepartmentStatJson(), 
                        new TypeReference<Map<String, Integer>>() {}));
            } else {
                stats.setDepartmentStat(new HashMap<>());
            }
        } catch (Exception e) {
            stats.setDepartmentStat(new HashMap<>());
        }
    }
}
