package com.scheduler.scheduler.service;

import com.scheduler.core.service.TaskExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;

@Slf4j
@Component
public class TaskExecutionJob implements Job {

    @Autowired
    private TaskExecutorService taskExecutorService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String taskId = context.getJobDetail().getJobDataMap().getString("taskId");

        log.info("Executing scheduled task: {}", taskId);

        try {
            taskExecutorService.executeTask(taskId, Map.of(
                    "scheduled", "true",
                    "fireTime", context.getFireTime().toString(),
                    "jobRunTime", context.getJobRunTime()
            )).subscribe(
                    execution -> log.info("Scheduled task {} completed successfully, runId: {}",
                            taskId, execution.getRunId()),
                    error -> {
                        log.error("Scheduled task {} execution failed", taskId, error);
                        throw new JobExecutionException(error);
                    }
            );
        } catch (Exception e) {
            log.error("Failed to execute scheduled task: {}", taskId, e);
            throw new JobExecutionException(e);
        }
    }
}
