package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "document_task", autoResultMap = true)
public class DocumentTask extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String taskId;

    private String pipelineId;

    private String fileName;

    private String filePath;

    private Long fileSize;

    private String fileType;

    private String status;

    private String phase;

    private BigDecimal progress;

    private Integer totalChunks;

    private String vectorStore;

    private String errorDetail;

    private Integer retryCount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;
}
