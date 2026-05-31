package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_vector_index")
public class SysVectorIndex extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String indexId;

    private String indexName;

    private String description;

    private Integer dimension;

    private String similarity;

    private String indexType;

    private Long vectorCount;

    private String storagePath;

    private String status;

    private Map<String, Object> buildConfig;

    private LocalDateTime builtAt;

    private LocalDateTime lastUpdatedAt;
}
