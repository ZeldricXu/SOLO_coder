package com.deviceops.service.analysis;

import com.deviceops.entity.DeviceStatistics;
import com.deviceops.entity.FaultRecord;
import com.deviceops.entity.OperationTask;
import com.deviceops.repository.DeviceStatisticsRepository;
import com.deviceops.repository.FaultRecordRepository;
import com.deviceops.repository.OperationTaskRepository;
import com.deviceops.service.device.DeviceService;
import com.deviceops.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AnalysisService {

    @Autowired
    private DeviceStatisticsRepository statisticsRepository;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private FaultRecordRepository faultRecordRepository;

    @Autowired
    private OperationTaskRepository taskRepository;

    public Map<String, Object> getOverviewStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("totalDevices", deviceService.count());
        stats.put("normalDevices", deviceService.countByStatus("normal"));
        stats.put("abnormalDevices", deviceService.countByStatus("abnormal"));
        stats.put("warningDevices", deviceService.countByStatus("warning"));
        
        stats.put("pendingFaults", faultRecordRepository.countByFaultStatus("pending"));
        stats.put("processingFaults", faultRecordRepository.countByFaultStatus("processing"));
        stats.put("resolvedFaults", faultRecordRepository.countByFaultStatus("resolved"));
        
        stats.put("pendingTasks", taskRepository.countByTaskStatus("pending"));
        stats.put("processingTasks", taskRepository.countByTaskStatus("processing"));
        stats.put("completedTasks", taskRepository.countByTaskStatus("completed"));
        
        return stats;
    }

    @Transactional
    public DeviceStatistics getMonthlyStatistics(String statMonth) {
        Optional<DeviceStatistics> existing = statisticsRepository.findByStatMonth(statMonth);
        if (existing.isPresent()) {
            return existing.get();
        }
        return calculateAndSaveMonthlyStatistics(statMonth);
    }

    @Transactional
    public DeviceStatistics calculateAndSaveMonthlyStatistics(String statMonth) {
        YearMonth month = YearMonth.parse(statMonth);
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.atEndOfMonth().atTime(23, 59, 59);

        long deviceCount = deviceService.count();
        long faultCount = faultRecordRepository.countByReportedAtBetween(start, end);
        long taskCount = taskRepository.countByTaskTimeBetween(start, end);
        double avgResponseTime = calculateAverageResponseTime(start, end);

        DeviceStatistics stats = new DeviceStatistics();
        stats.setStatId(IdGenerator.generateStatId());
        stats.setStatMonth(statMonth);
        stats.setDeviceCount((int) deviceCount);
        stats.setFaultCount((int) faultCount);
        stats.setTaskCount((int) taskCount);
        stats.setAvgResponseTime(avgResponseTime);

        return statisticsRepository.save(stats);
    }

    private double calculateAverageResponseTime(LocalDateTime start, LocalDateTime end) {
        List<OperationTask> completedTasks = taskRepository.findByTaskStatus("completed");
        
        if (completedTasks.isEmpty()) {
            return 0.0;
        }

        double totalHours = 0.0;
        int count = 0;

        for (OperationTask task : completedTasks) {
            if (task.getTaskTime() != null && task.getCompletedAt() != null) {
                if (task.getTaskTime().isAfter(start.minusDays(1)) && task.getTaskTime().isBefore(end.plusDays(1))) {
                    long hours = ChronoUnit.HOURS.between(task.getTaskTime(), task.getCompletedAt());
                    totalHours += Math.max(hours, 1);
                    count++;
                }
            }
        }

        return count > 0 ? totalHours / count : 0.0;
    }

    @Transactional
    public void updateStatistics() {
        String currentMonth = YearMonth.now().toString();
        calculateAndSaveMonthlyStatistics(currentMonth);
    }

    @Transactional
    public void incrementFaultCount() {
        updateStatistics();
    }

    @Transactional
    public void incrementTaskCount() {
        updateStatistics();
    }

    public Map<String, Object> getDeviceStatusDistribution() {
        Map<String, Object> distribution = new HashMap<>();
        distribution.put("normal", deviceService.countByStatus("normal"));
        distribution.put("warning", deviceService.countByStatus("warning"));
        distribution.put("abnormal", deviceService.countByStatus("abnormal"));
        return distribution;
    }

    public Map<String, Object> getFaultStatusDistribution() {
        Map<String, Object> distribution = new HashMap<>();
        distribution.put("pending", faultRecordRepository.countByFaultStatus("pending"));
        distribution.put("processing", faultRecordRepository.countByFaultStatus("processing"));
        distribution.put("resolved", faultRecordRepository.countByFaultStatus("resolved"));
        return distribution;
    }

    public Map<String, Object> getTaskStatusDistribution() {
        Map<String, Object> distribution = new HashMap<>();
        distribution.put("pending", taskRepository.countByTaskStatus("pending"));
        distribution.put("processing", taskRepository.countByTaskStatus("processing"));
        distribution.put("completed", taskRepository.countByTaskStatus("completed"));
        return distribution;
    }

    public List<DeviceStatistics> getAllStatistics() {
        return statisticsRepository.findAll();
    }
}
