package com.solocoder.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("schema_version")
public class SchemaVersionEntity {

    @TableId(type = IdType.INPUT)
    private String version;

    private String description;

    private String script;

    private Integer checksum;

    private String installedBy;

    private Instant installedOn;

    private Integer executionTime;

    private Boolean success;
}
