package com.taskscheduler.service;

import com.taskscheduler.entity.Dependency;
import com.taskscheduler.entity.ExecuteRecord;
import com.taskscheduler.exception.DependencyNotCompletedException;
import com.taskscheduler.repository.DependencyRepository;
import com.taskscheduler.repository.ExecuteRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyService {

    private final DependencyRepository dependencyRepository;
    private final ExecuteRecordRepository executeRecordRepository;

    public boolean checkDependenciesCompleted(String taskId) {
        List<String> dependencies = dependencyRepository.findDependenciesByTaskId(taskId);
        
        if (dependencies.isEmpty()) {
            return true;
        }

        for (String dependsOn : dependencies) {
            if (!isLastExecutionSuccess(dependsOn)) {
                log.warn("Dependency check failed for task: {}, dependency: {} not completed successfully", taskId, dependsOn);
                return false;
            }
        }
        
        return true;
    }

    public boolean isLastExecutionSuccess(String taskId) {
        List<ExecuteRecord> records = executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(taskId);
        
        if (records.isEmpty()) {
            return false;
        }
        
        return "success".equals(records.get(0).getExecuteStatus());
    }

    public void validateDependenciesOrThrow(String taskId) {
        List<String> dependencies = dependencyRepository.findDependenciesByTaskId(taskId);
        
        if (dependencies.isEmpty()) {
            return;
        }

        for (String dependsOn : dependencies) {
            if (!isLastExecutionSuccess(dependsOn)) {
                throw new DependencyNotCompletedException(taskId, dependsOn);
            }
        }
    }

    public List<Dependency> getTaskDependencies(String taskId) {
        return dependencyRepository.findByTaskId(taskId);
    }

    public List<String> getDependentTasks(String taskId) {
        return dependencyRepository.findDependentTasks(taskId);
    }

    public void addDependency(String taskId, String dependsOn) {
        if (dependencyRepository.existsByTaskIdAndDependsOn(taskId, dependsOn)) {
            return;
        }
        
        Dependency dependency = new Dependency();
        dependency.setTaskId(taskId);
        dependency.setDependsOn(dependsOn);
        dependency.setDependencyType("sequential");
        
        dependencyRepository.save(dependency);
        log.info("Added dependency: {} -> {}", taskId, dependsOn);
    }

    public void removeDependency(String taskId, String dependsOn) {
        List<Dependency> dependencies = dependencyRepository.findByTaskId(taskId);
        for (Dependency dep : dependencies) {
            if (dependsOn.equals(dep.getDependsOn())) {
                dependencyRepository.delete(dep);
                log.info("Removed dependency: {} -> {}", taskId, dependsOn);
                break;
            }
        }
    }
}
