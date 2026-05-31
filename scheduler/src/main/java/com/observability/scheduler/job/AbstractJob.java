package com.observability.scheduler.job;

import com.observability.common.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@Slf4j
public abstract class AbstractJob implements Job {

    @Override
    public final void execute(JobExecutionContext context) throws JobExecutionException {
        String jobName = context.getJobDetail().getKey().getName();
        String executionId = IdGenerator.generateId("exec");
        long startTime = System.currentTimeMillis();

        log.info("Job started - jobName: {}, executionId: {}", jobName, executionId);

        try {
            doExecute(context);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Job completed - jobName: {}, executionId: {}, duration: {}ms",
                    jobName, executionId, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Job failed - jobName: {}, executionId: {}, duration: {}ms, error: {}",
                    jobName, executionId, duration, e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }

    protected abstract void doExecute(JobExecutionContext context) throws Exception;
}
