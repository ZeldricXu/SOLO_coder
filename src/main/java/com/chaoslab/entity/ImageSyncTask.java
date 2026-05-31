package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("image_sync_task")
public class ImageSyncTask extends BaseEntity {

    private String taskId;
    private String sourceRepoId;
    private String targetRepoId;
    private String imageReference;
    private String strategy;
    private Boolean p2pEnabled;
    private String status;
    private BigDecimal progress;
    private Integer totalLayers;
    private Integer completedLayers;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorDetail;
}
