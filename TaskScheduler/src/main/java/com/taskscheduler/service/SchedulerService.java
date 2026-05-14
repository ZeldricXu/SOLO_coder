package com.taskscheduler.service;

import com.taskscheduler.entity.TaskConfig;
import com.taskscheduler.repository.TaskConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final org.quartz.Scheduler scheduler;
    private final TaskConfigRepository taskConfigRepository;
    private final DispatcherService dispatcherService;
    private final ExecutorService heartbeatExecutor = Executors.newFixedThreadPool(2);

    @PostConstruct
    public void init() {
        try {
            scheduler.getContext().put("dispatcherService", dispatcherService);
            scheduler.start();
            log.info("Quartz scheduler started");
        } catch (SchedulerException e) {
            log.error("Failed to start Quartz scheduler", e);
        }
    }

    @Transactional
    public void scheduleTask(TaskConfig taskConfig) {
        if (taskConfig.getCronExpression() == null || taskConfig.getCronExpression().isEmpty()) {
            log.warn("No cron expression for task: {}", taskConfig.getTaskId());
            return;
        }

        try {
            String jobKey = "job_" + taskConfig.getTaskId();
            String triggerKey = "trigger_" + taskConfig.getTaskId();

            JobDetail jobDetail = JobBuilder.newJob(TaskDispatchJob.class)
                    .withIdentity(jobKey, "tasks")
                    .usingJobData("taskId", taskConfig.getTaskId())
                    .storeDurably()
                    .build();

            CronScheduleBuilder scheduleBuilder = CronScheduleBuilder
                    .cronSchedule(taskConfig.getCronExpression());

            if (taskConfig.getTimezone() != null) {
                scheduleBuilder.inTimeZone(TimeZone.getTimeZone(taskConfig.getTimezone()));
            }

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey, "tasks")
                    .withSchedule(scheduleBuilder)
                    .build();

            if (scheduler.checkExists(JobKey.jobKey(jobKey, "tasks"))) {
                scheduler.deleteJob(JobKey.jobKey(jobKey, "tasks"));
            }

            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Task scheduled: {} with cron: {}", taskConfig.getTaskId(), taskConfig.getCronExpression());

        } catch (SchedulerException e) {
            log.error("Failed to schedule task: {}", taskConfig.getTaskId(), e);
            throw new RuntimeException("Failed to schedule task: " + taskConfig.getTaskId(), e);
        }
    }

    @Transactional
    public void unscheduleTask(String taskId) {
        try {
            String jobKey = "job_" + taskId;
            String triggerKey = "trigger_" + taskId;

            if (scheduler.checkExists(TriggerKey.triggerKey(triggerKey, "tasks"))) {
                scheduler.unscheduleJob(TriggerKey.triggerKey(triggerKey, "tasks"));
            }

            if (scheduler.checkExists(JobKey.jobKey(jobKey, "tasks"))) {
                scheduler.deleteJob(JobKey.jobKey(jobKey, "tasks"));
            }

            log.info("Task unscheduled: {}", taskId);
        } catch (SchedulerException e) {
            log.error("Failed to unschedule task: {}", taskId, e);
        }
    }

    public void triggerTask(String taskId, String triggerType) {
        dispatcherService.triggerAndDispatch(taskId, triggerType);
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void rescheduleAllTasks() {
        List<TaskConfig> scheduledTasks = taskConfigRepository.findAllScheduledTasks();
        for (TaskConfig task : scheduledTasks) {
            try {
                scheduleTask(task);
            } catch (Exception e) {
                log.error("Failed to reschedule task: {}", task.getTaskId(), e);
            }
        }
    }

    public static class TaskDispatchJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            JobDataMap dataMap = context.getJobDetail().getJobDataMap();
            String taskId = dataMap.getString("taskId");

            try {
                DispatcherService dispatcher = (DispatcherService) context.getScheduler().getContext().get("dispatcherService");
                if (dispatcher != null) {
                    dispatcher.triggerAndDispatch(taskId, "scheduled");
                }
            } catch (Exception e) {
                throw new JobExecutionException("Failed to dispatch task: " + taskId, e);
            }
        }
    }
}
