package com.delivery.tracker.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.delivery.tracker.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fl_training_task")
public class FLTrainingTask extends BaseEntity {

    private String taskId;

    private String modelName;

    private String status;

    private Integer currentRound;

    private Integer totalRounds;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> participants;

    private String globalModelPath;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;
}
