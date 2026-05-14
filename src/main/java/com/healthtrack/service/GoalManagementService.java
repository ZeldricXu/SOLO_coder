package com.healthtrack.service;

import com.healthtrack.entity.HealthGoal;
import com.healthtrack.repository.HealthGoalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class GoalManagementService {

    @Autowired
    private HealthGoalRepository healthGoalRepository;

    @Autowired
    private HistoryService historyService;

    public void checkGoals(String userId, String dataType, Double currentValue) {
        List<HealthGoal> goals = healthGoalRepository.findByUserIdAndStatus(userId, "in_progress");
        
        for (HealthGoal goal : goals) {
            if (goal.getGoalType().equalsIgnoreCase(dataType)) {
                updateGoalProgress(goal, currentValue);
            }
        }
    }

    private void updateGoalProgress(HealthGoal goal, Double currentValue) {
        Double oldValue = goal.getCurrentValue();
        goal.setCurrentValue(currentValue);
        
        int progress = calculateProgress(goal);
        goal.setProgress(progress);
        
        if (isGoalAchieved(goal)) {
            goal.setStatus("achieved");
            historyService.recordHistory(goal.getUserId(), goal.getGoalType(), "GOAL_ACHIEVED", 
                    oldValue, currentValue, "目标达成: " + goal.getGoalType());
        }
        
        healthGoalRepository.save(goal);
    }

    private int calculateProgress(HealthGoal goal) {
        Double start = goal.getStartValue();
        Double target = goal.getTargetValue();
        Double current = goal.getCurrentValue();
        
        if (start.equals(target)) {
            return 100;
        }
        
        double totalDiff = Math.abs(target - start);
        double currentDiff = Math.abs(current - start);
        
        if (totalDiff == 0) {
            return 100;
        }
        
        int progress = (int) Math.round((currentDiff / totalDiff) * 100);
        return Math.min(Math.max(progress, 0), 100);
    }

    private boolean isGoalAchieved(HealthGoal goal) {
        Double current = goal.getCurrentValue();
        Double target = goal.getTargetValue();
        
        if (goal.getStartValue() > target) {
            return current <= target;
        } else {
            return current >= target;
        }
    }

    public HealthGoal createGoal(HealthGoal goal) {
        goal.setGoalId("goal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        goal.setStatus("in_progress");
        goal.setProgress(0);
        goal.setCurrentValue(goal.getStartValue());
        return healthGoalRepository.save(goal);
    }

    public List<HealthGoal> getUserGoals(String userId) {
        return healthGoalRepository.findByUserId(userId);
    }

    public Optional<HealthGoal> getGoalById(String goalId) {
        return healthGoalRepository.findById(goalId);
    }

    public HealthGoal updateGoal(String goalId, HealthGoal updatedGoal) {
        return healthGoalRepository.findById(goalId)
                .map(goal -> {
                    goal.setTargetValue(updatedGoal.getTargetValue());
                    goal.setDeadline(updatedGoal.getDeadline());
                    goal.setDescription(updatedGoal.getDescription());
                    return healthGoalRepository.save(goal);
                })
                .orElseThrow(() -> new IllegalArgumentException("目标不存在: " + goalId));
    }

    public void deleteGoal(String goalId) {
        healthGoalRepository.deleteById(goalId);
    }
}
