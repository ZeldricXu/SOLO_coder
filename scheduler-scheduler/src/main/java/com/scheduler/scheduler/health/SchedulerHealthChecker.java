package com.scheduler.scheduler.health;

import com.scheduler.data.repository.ScheduledTaskRepository;
import com.scheduler.persistence.entity.ScheduledTask;
import com.scheduler.scheduler.core.QuartzTaskScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerHealthChecker {

    private final ScheduledTaskRepository taskRepository;
    private final QuartzTaskScheduler taskScheduler;

    @Scheduled(fixedDelay = 5000)
    public void checkMissedExecutions() {
        List<ScheduledTask> tasks = taskRepository.findTasksToExecute();
        for (ScheduledTask task : tasks) {
            log.warn("Found missed execution for task: {}, nextExecutionTime was: {}",
                    task.getTaskId(), task.getNextExecutionTime());
            updateNextExecutionTime(task);
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void updateAllNextExecutionTimes() {
        taskScheduler.getRegisteredJobs().keySet().forEach(taskId -> {
            try {
                ScheduledTask task = taskRepository.findById(taskId);
                updateNextExecutionTime(task);
            } catch (Exception e) {
                log.debug("Failed to update next execution time for task: {}", taskId);
            }
        });
    }

    public void updateNextExecutionTime(ScheduledTask task) {
        java.util.Date nextFireTime = taskScheduler.getNextFireTime(task);
        if (nextFireTime != null) {
            task.setNextExecutionTime(nextFireTime.toInstant());
            taskRepository.update(task);
        }
    }

    public List<Map<String, Object>> getUpcomingExecutions(int limit) {
        List<Map<String, Object>> executions = new ArrayList<>();
        try {
            Scheduler scheduler = taskScheduler.getScheduler();
            for (String group : scheduler.getJobGroupNames()) {
                for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(group))) {
                    List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
                    for (Trigger trigger : triggers) {
                        if (trigger.getNextFireTime() != null) {
                            executions.add(Map.of(
                                    "taskId", jobKey.getName(),
                                    "namespace", jobKey.getGroup(),
                                    "nextFireTime", trigger.getNextFireTime().toInstant().toString(),
                                    "previousFireTime", trigger.getPreviousFireTime() != null
                                            ? trigger.getPreviousFireTime().toInstant().toString() : null
                            ));
                        }
                    }
                }
            }
            executions.sort((a, b) -> Instant.parse((String) a.get("nextFireTime"))
                    .compareTo(Instant.parse((String) b.get("nextFireTime"))));
            return executions.subList(0, Math.min(limit, executions.size()));
        } catch (SchedulerException e) {
            log.error("Failed to get upcoming executions", e);
            return executions;
        }
    }
}
