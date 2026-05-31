package com.scheduler.scheduler.trigger;

import com.scheduler.persistence.entity.ScheduledTask;
import org.quartz.*;
import org.springframework.stereotype.Component;

@Component
public class TriggerFactory {

    public Trigger buildTrigger(ScheduledTask task, JobKey jobKey) {
        String namespace = task.getNamespace() != null ? task.getNamespace() : "default";
        TriggerKey triggerKey = TriggerKey.triggerKey(task.getTaskId() + "_trigger", namespace);

        TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey);

        if (task.getCronExpression() != null && !task.getCronExpression().isEmpty()) {
            builder.withSchedule(CronScheduleBuilder.cronSchedule(task.getCronExpression()));
        } else if (task.getFixedRate() != null && task.getFixedRate() > 0) {
            builder.withSchedule(SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInMilliseconds(task.getFixedRate())
                    .repeatForever());
        } else if (task.getFixedDelay() != null && task.getFixedDelay() > 0) {
            builder.withSchedule(SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInMilliseconds(task.getFixedDelay())
                    .repeatForever());
        } else {
            builder.withSchedule(SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInHours(1)
                    .repeatForever());
        }

        return builder.build();
    }

    public JobKey buildJobKey(String taskId, String namespace) {
        String ns = namespace != null ? namespace : "default";
        return JobKey.jobKey(taskId, ns);
    }

    public TriggerKey buildTriggerKey(String taskId, String namespace) {
        String ns = namespace != null ? namespace : "default";
        return TriggerKey.triggerKey(taskId + "_trigger", ns);
    }
}
