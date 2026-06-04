package com.cicd.server.pipeline;

import com.cicd.common.enums.TriggerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Service;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineScheduler {

    private final Scheduler scheduler;
    private final PipelineService pipelineService;

    public void schedulePipeline(Long pipelineId, String cronExpression, Map<String, String> params) {
        try {
            JobKey jobKey = JobKey.jobKey("pipeline-" + pipelineId, "pipeline-jobs");
            TriggerKey triggerKey = TriggerKey.triggerKey("pipeline-" + pipelineId, "pipeline-triggers");

            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }

            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put("pipelineId", pipelineId);
            if (params != null) {
                jobDataMap.put("params", params);
            }

            JobDetail job = JobBuilder.newJob(PipelineScheduledJob.class)
                .withIdentity(jobKey)
                .usingJobData(jobDataMap)
                .storeDurably()
                .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                .build();

            scheduler.scheduleJob(job, trigger);
            log.info("Scheduled pipeline {} with cron: {}", pipelineId, cronExpression);
        } catch (SchedulerException e) {
            log.error("Failed to schedule pipeline {}", pipelineId, e);
            throw new RuntimeException("Failed to schedule pipeline", e);
        }
    }

    public void unschedulePipeline(Long pipelineId) {
        try {
            JobKey jobKey = JobKey.jobKey("pipeline-" + pipelineId, "pipeline-jobs");
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("Unscheduled pipeline {}", pipelineId);
            }
        } catch (SchedulerException e) {
            log.error("Failed to unschedule pipeline {}", pipelineId, e);
        }
    }

    @DisallowConcurrentExecution
    public static class PipelineScheduledJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                JobDataMap dataMap = context.getJobDetail().getJobDataMap();
                Long pipelineId = dataMap.getLong("pipelineId");
                @SuppressWarnings("unchecked")
                Map<String, String> params = (Map<String, String>) dataMap.get("params");

                PipelineService pipelineService = SpringContextHolder.getBean(PipelineService.class);
                if (pipelineService != null) {
                    pipelineService.triggerPipeline(pipelineId, TriggerType.SCHEDULED, "scheduler", null, params);
                    log.info("Triggered scheduled pipeline {}", pipelineId);
                }
            } catch (Exception e) {
                log.error("Failed to execute scheduled pipeline job", e);
                throw new JobExecutionException(e);
            }
        }
    }
}
