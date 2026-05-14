package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.model.Risk;
import com.projmanage.model.Task;
import com.projmanage.repository.RiskRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class RiskService {

    private final RiskRepository riskRepository;
    private final CollaborationService collaborationService;

    public RiskService(RiskRepository riskRepository, CollaborationService collaborationService) {
        this.riskRepository = riskRepository;
        this.collaborationService = collaborationService;
    }

    @Transactional
    public Risk createRisk(String projectId, String taskId, String riskType,
                           String riskDescription, String riskLevel) {
        Risk risk = new Risk();
        risk.setRiskId(IdGenerator.generateRiskId());
        risk.setProjectId(projectId);
        risk.setTaskId(taskId);
        risk.setRiskType(riskType);
        risk.setRiskDescription(riskDescription);
        risk.setRiskLevel(riskLevel);
        risk.setRiskStatus(Constants.RISK_STATUS_IDENTIFIED);
        risk.setIdentifiedAt(LocalDateTime.now());

        Risk savedRisk = riskRepository.save(risk);

        collaborationService.sendNotification(
                "system",
                projectId,
                taskId,
                Constants.NOTIFICATION_TYPE_RISK_ALERT,
                "风险告警",
                "检测到风险: " + riskDescription + ", 风险等级: " + riskLevel
        );

        return savedRisk;
    }

    public Optional<Risk> getRiskById(String riskId) {
        return riskRepository.findById(riskId);
    }

    public List<Risk> getRisksByProject(String projectId) {
        return riskRepository.findByProjectId(projectId);
    }

    public List<Risk> getRisksByTask(String taskId) {
        return riskRepository.findByTaskId(taskId);
    }

    public List<Risk> getActiveRisksByProject(String projectId) {
        return riskRepository.findByProjectIdAndRiskStatus(projectId, Constants.RISK_STATUS_IDENTIFIED);
    }

    @Transactional
    public void checkTaskRisk(Task task) {
        if (task.getDueDate() == null) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate dueDate = task.getDueDate();
        long daysUntil = today.until(dueDate, java.time.temporal.ChronoUnit.DAYS);

        Integer progress = task.getProgress() != null ? task.getProgress() : 0;
        String status = task.getTaskStatus();

        if (daysUntil < 0 && !Constants.TASK_STATUS_COMPLETED.equals(status)) {
            createRisk(
                    task.getProjectId(),
                    task.getTaskId(),
                    Constants.RISK_TYPE_SCHEDULE_DELAY,
                    "任务 [" + task.getTaskName() + "] 已延期 " + Math.abs(daysUntil) + " 天",
                    Constants.RISK_LEVEL_HIGH
            );
        } else if (daysUntil <= 3 && daysUntil >= 0 && progress < 80 && !Constants.TASK_STATUS_COMPLETED.equals(status)) {
            String riskLevel = daysUntil <= 1 ? Constants.RISK_LEVEL_HIGH : Constants.RISK_LEVEL_MEDIUM;
            createRisk(
                    task.getProjectId(),
                    task.getTaskId(),
                    Constants.RISK_TYPE_SCHEDULE_DELAY,
                    "任务 [" + task.getTaskName() + "] 距离截止日期还有 " + daysUntil + " 天，当前进度 " + progress + "%",
                    riskLevel
            );
        }
    }

    @Transactional
    public void updateRiskStatus(String riskId, String newStatus) {
        Optional<Risk> riskOpt = riskRepository.findById(riskId);
        if (riskOpt.isPresent()) {
            Risk risk = riskOpt.get();
            risk.setRiskStatus(newStatus);
            if (Constants.RISK_STATUS_RESOLVED.equals(newStatus)) {
                risk.setResolvedAt(LocalDateTime.now());
            }
            riskRepository.save(risk);
        }
    }
}
