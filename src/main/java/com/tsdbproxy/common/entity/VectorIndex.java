package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_vector_index")
public class VectorIndex extends BaseEntity {

    private String name;

    private Integer dimension;

    private String metricType;

    private String indexType;

    private Long totalVectors;

    private String status;

    private String indexParams;

    private LocalDateTime lastBuildTime;
}
