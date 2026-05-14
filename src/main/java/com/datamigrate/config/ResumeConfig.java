package com.datamigrate.config;

import com.datamigrate.common.CheckpointType;
import com.datamigrate.common.ResumeStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private CheckpointType checkpointType = CheckpointType.BY_BATCH;

    @Builder.Default
    private long checkpointRecordInterval = 1000L;

    @Builder.Default
    private Duration checkpointTimeInterval = Duration.ofSeconds(30);

    @Builder.Default
    private ResumeStrategy resumeStrategy = ResumeStrategy.FROM_BREAKPOINT;

    @Builder.Default
    private boolean saveProgressToDatabase = true;

    @Builder.Default
    private boolean saveProgressToRedis = false;

    @Builder.Default
    private int maxResumeAttempts = 10;

    @Builder.Default
    private Duration resumeTimeout = Duration.ofMinutes(10);

    private static final ResumeConfig DEFAULT = ResumeConfig.builder()
        .enabled(true)
        .checkpointType(CheckpointType.BY_BATCH)
        .checkpointRecordInterval(1000L)
        .checkpointTimeInterval(Duration.ofSeconds(30))
        .resumeStrategy(ResumeStrategy.FROM_BREAKPOINT)
        .saveProgressToDatabase(true)
        .saveProgressToRedis(false)
        .maxResumeAttempts(10)
        .resumeTimeout(Duration.ofMinutes(10))
        .build();

    public static ResumeConfig getDefault() {
        return DEFAULT;
    }

    public static ResumeConfig fromTask(com.datamigrate.entity.MigrateTask task) {
        if (task == null) {
            return getDefault();
        }

        ResumeConfigBuilder builder = ResumeConfig.builder()
            .taskId(task.getTaskId());

        if (task.getResumeStrategy() != null) {
            builder.resumeStrategy(task.getResumeStrategy());
        }
        if (task.getCheckpointType() != null) {
            builder.checkpointType(task.getCheckpointType());
        }
        if (task.getCheckpointInterval() != null) {
            builder.checkpointRecordInterval(task.getCheckpointInterval());
        }

        return builder.build();
    }
}
