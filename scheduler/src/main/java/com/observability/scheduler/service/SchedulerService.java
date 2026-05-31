package com.observability.scheduler.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.observability.common.exception.BusinessException;
import com.observability.common.util.IdGenerator;
import com.observability.scheduler.entity.ScheduledJobEntity;
import com.observability.scheduler.job.AbstractJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final Scheduler scheduler;
    private final ApplicationContext applicationContext;

    public Mono<ScheduledJobEntity> createJob(String name, String cronExpression,
                                               String jobType, Map<String, Object> jobParams) {
        return Mono.fromCallable(() -> {
            String jobId = IdGenerator.generateJobId();

            ScheduledJobEntity job = new ScheduledJobEntity();
            job.setJobId(jobId);
            job.setName(name);
            job.setCronExpression(cronExpression);
            job.setJobType(jobType);
            job.setJobParams(jobParams);
            job.setStatus("stopped");

            scheduleJob(job);

            log.info("Job created - jobId: {}, name: {}, cron: {}", jobId, name, cronExpression);
            return job;
        });
    }

    public Mono<Void> startJob(String jobId) {
        return Mono.fromRunnable(() -> {
            ScheduledJobEntity job = getJobEntity(jobId);
            if (!"running".equals(job.getStatus())) {
                resumeJob(job);
                job.setStatus("running");
                log.info("Job started - jobId: {}", jobId);
            }
        });
    }

    public Mono<Void> stopJob(String jobId) {
        return Mono.fromRunnable(() -> {
            ScheduledJobEntity job = getJobEntity(jobId);
            if (!"stopped".equals(job.getStatus())) {
                pauseJob(job);
                job.setStatus("stopped");
                log.info("Job stopped - jobId: {}", jobId);
            }
        });
    }

    public Mono<Void> deleteJob(String jobId) {
        return Mono.fromRunnable(() -> {
            ScheduledJobEntity job = getJobEntity(jobId);
            deleteJobFromScheduler(job);
            log.info("Job deleted - jobId: {}", jobId);
        });
    }

    public Mono<List<ScheduledJobEntity>> listJobs() {
        return Mono.fromCallable(() -> {
            try {
                return scheduler.getJobKeys(GroupMatcher.anyGroup()).stream()
                        .map(jobKey -> {
                            ScheduledJobEntity entity = new ScheduledJobEntity();
                            entity.setJobId(jobKey.getName());
                            entity.setName(jobKey.getName());
                            try {
                                Trigger trigger = scheduler.getTriggersOfJob(jobKey).get(0);
                                if (trigger instanceof CronTrigger cronTrigger) {
                                    entity.setCronExpression(cronTrigger.getCronExpression());
                                }
                                entity.setNextRunAt(convertToLocalDateTime(trigger.getNextFireTime()));
                                entity.setLastRunAt(convertToLocalDateTime(trigger.getPreviousFireTime()));
                                Trigger.TriggerState state = scheduler.getTriggerState(trigger.getKey());
                                entity.setState(state.name());
                            } catch (SchedulerException e) {
                                log.error("Failed to get job details", e);
                            }
                            return entity;
                        })
                        .toList();
            } catch (SchedulerException e) {
                throw new RuntimeException("Failed to list jobs", e);
            }
        });
    }

    public Mono<ScheduledJobEntity> getJob(String jobId) {
        return Mono.fromCallable(() -> getJobEntity(jobId));
    }

    private ScheduledJobEntity getJobEntity(String jobId) {
        ScheduledJobEntity job = new ScheduledJobEntity();
        job.setJobId(jobId);
        try {
            JobKey jobKey = JobKey.jobKey(jobId);
            if (!scheduler.checkExists(jobKey)) {
                throw BusinessException.notFound("Job not found: " + jobId);
            }
            Trigger trigger = scheduler.getTriggersOfJob(jobKey).get(0);
            if (trigger instanceof CronTrigger cronTrigger) {
                job.setCronExpression(cronTrigger.getCronExpression());
            }
            job.setNextRunAt(convertToLocalDateTime(trigger.getNextFireTime()));
            job.setLastRunAt(convertToLocalDateTime(trigger.getPreviousFireTime()));
            Trigger.TriggerState state = scheduler.getTriggerState(trigger.getKey());
            job.setState(state.name());
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to get job: " + jobId, e);
        }
        return job;
    }

    @SuppressWarnings("unchecked")
    private void scheduleJob(ScheduledJobEntity job) {
        try {
            Class<? extends AbstractJob> jobClass = (Class<? extends AbstractJob>)
                    Class.forName("com.observability.scheduler.job." + job.getJobType() + "Job");
            JobDetail jobDetail = JobBuilder.newJob(jobClass)
                    .withIdentity(job.getJobId(), "default")
                    .storeDurably()
                    .build();

            if (job.getJobParams() != null) {
                job.getJobParams().forEach((key, value) ->
                        jobDetail.getJobDataMap().put(key, value));
            }

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(job.getJobId() + "-trigger", "default")
                    .withSchedule(CronScheduleBuilder.cronSchedule(job.getCronExpression()))
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
        } catch (ClassNotFoundException e) {
            throw BusinessException.validationError("Unknown job type: " + job.getJobType());
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to schedule job", e);
        }
    }

    private void pauseJob(ScheduledJobEntity job) {
        try {
            scheduler.pauseJob(JobKey.jobKey(job.getJobId(), "default"));
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to pause job", e);
        }
    }

    private void resumeJob(ScheduledJobEntity job) {
        try {
            scheduler.resumeJob(JobKey.jobKey(job.getJobId(), "default"));
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to resume job", e);
        }
    }

    private void deleteJobFromScheduler(ScheduledJobEntity job) {
        try {
            scheduler.deleteJob(JobKey.jobKey(job.getJobId(), "default"));
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to delete job", e);
        }
    }

    private LocalDateTime convertToLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
