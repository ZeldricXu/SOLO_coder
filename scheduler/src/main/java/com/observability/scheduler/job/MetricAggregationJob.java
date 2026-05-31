package com.observability.scheduler.job;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MetricAggregationJob extends AbstractJob {

    @Override
    protected void doExecute(JobExecutionContext context) {
        String metricName = context.getJobDetail().getJobDataMap().getString("metricName");
        log.info("Aggregating metric: {}", metricName);
    }
}
