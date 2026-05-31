package com.scheduler.scheduler.core;

import com.scheduler.persistence.entity.ScheduledTask;
import com.scheduler.scheduler.service.TaskExecutionJob;
import com.scheduler.scheduler.trigger.TriggerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuartzTaskScheduler {

    private final Scheduler scheduler;
    private final TriggerFactory triggerFactory;
    private final Map<String, JobDetail> registeredJobs = new ConcurrentHashMap<>();

    public void scheduleTask(ScheduledTask task) {
        try {
            JobKey jobKey = triggerFactory.buildJobKey(task.getTaskId(), task.getNamespace());

            if (registeredJobs.containsKey(task.getTaskId())) {
                unscheduleTask(task);
            }

            JobDetail job = JobBuilder.newJob(TaskExecutionJob.class)
                    .withIdentity(jobKey)
                    .usingJobData("taskId", task.getTaskId())
                    .storeDurably()
                    .build();

            Trigger trigger = triggerFactory.buildTrigger(task, jobKey);
            scheduler.scheduleJob(job, trigger);
            registeredJobs.put(task.getTaskId(), job);

            log.info("Scheduled task: {} with trigger: {}", task.getTaskId(), trigger.getKey());
        } catch (SchedulerException e) {
            log.error("Failed to schedule task: {}", task.getTaskId(), e);
            throw new RuntimeException("Failed to schedule task", e);
        }
    }

    public void unscheduleTask(ScheduledTask task) {
        try {
            JobKey jobKey = triggerFactory.buildJobKey(task.getTaskId(), task.getNamespace());
            TriggerKey triggerKey = triggerFactory.buildTriggerKey(task.getTaskId(), task.getNamespace());

            if (scheduler.checkExists(triggerKey)) {
                scheduler.unscheduleJob(triggerKey);
            }
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }
            registeredJobs.remove(task.getTaskId());
            log.debug("Unscheduled task: {}", task.getTaskId());
        } catch (SchedulerException e) {
            log.error("Failed to unschedule task: {}", task.getTaskId(), e);
        }
    }

    public Date getNextFireTime(ScheduledTask task) {
        try {
            TriggerKey triggerKey = triggerFactory.buildTriggerKey(task.getTaskId(), task.getNamespace());
            Trigger trigger = scheduler.getTrigger(triggerKey);
            return trigger != null ? trigger.getNextFireTime() : null;
        } catch (SchedulerException e) {
            log.error("Failed to get next fire time for task: {}", task.getTaskId(), e);
            return null;
        }
    }

    public boolean isScheduled(String taskId) {
        return registeredJobs.containsKey(taskId);
    }

    public Map<String, JobDetail> getRegisteredJobs() {
        return registeredJobs;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }
}
