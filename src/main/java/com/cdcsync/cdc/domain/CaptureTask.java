package com.cdcsync.cdc.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_capture_task")
public class CaptureTask extends BaseEntity {

    private String dataSourceId;

    private String name;

    private String tableList;

    private String startPosition;

    private String currentPosition;

    private String status;

    private String configJson;

    private LocalDateTime lastCaptureAt;

    private Long captureCount;

    private Long errorCount;
}
