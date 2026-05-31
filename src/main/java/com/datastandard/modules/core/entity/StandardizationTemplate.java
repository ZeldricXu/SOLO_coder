package com.datastandard.modules.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("standardization_templates")
public class StandardizationTemplate {

    @TableId(type = IdType.ASSIGN_UUID)
    private String templateId;

    private String templateName;

    private String dataSource;

    private String datasetName;

    private String config;

    private String version;

    private boolean active;

    private String createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    private Integer deleted;
}
