package com.observability.scheduler.job;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AlertEvaluationJob extends AbstractJob {

    @Override
    protected void doExecute(JobExecutionContext context) {
        String ruleId = context.getJobDetail().getJobDataMap().getString("ruleId");
        log.info("Evaluating alert rule: {}", ruleId);
    }
}
