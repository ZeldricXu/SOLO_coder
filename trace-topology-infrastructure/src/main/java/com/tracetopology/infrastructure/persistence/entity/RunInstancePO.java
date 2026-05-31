package com.tracetopology.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tracetopology.domain.entity.RunInstance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_run_instance")
public class RunInstancePO {

    @TableId(type = IdType.INPUT)
    private String runId;
    private String entityId;
    private String phase;
    private double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;

    public static RunInstancePO fromDomain(RunInstance runInstance) {
        return RunInstancePO.builder()
                .runId(runInstance.getRunId())
                .entityId(runInstance.getEntityId())
                .phase(runInstance.getPhase())
                .progress(runInstance.getProgress())
                .startedAt(runInstance.getStartedAt())
                .completedAt(runInstance.getCompletedAt())
                .errorDetail(runInstance.getErrorDetail())
                .build();
    }

    public RunInstance toDomain() {
        return RunInstance.builder()
                .runId(this.runId)
                .entityId(this.entityId)
                .phase(this.phase)
                .progress(this.progress)
                .startedAt(this.startedAt)
                .completedAt(this.completedAt)
                .errorDetail(this.errorDetail)
                .build();
    }
}
