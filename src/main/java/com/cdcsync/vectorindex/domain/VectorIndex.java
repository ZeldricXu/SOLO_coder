package com.cdcsync.vectorindex.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_vector_index")
public class VectorIndex extends BaseEntity {

    private String name;

    private Integer dimension;

    private String indexType;

    private String metricType;

    private Long vectorCount;

    private String indexPath;

    private String configJson;

    private String status;

    private LocalDateTime lastBuildAt;
}
