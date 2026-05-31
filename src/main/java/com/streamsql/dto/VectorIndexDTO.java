package com.streamsql.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Map;

@Data
public class VectorIndexDTO {

    @NotBlank(message = "索引名称不能为空")
    private String indexName;

    @NotBlank(message = "数据源ID不能为空")
    private String datasourceId;

    @NotBlank(message = "表名不能为空")
    private String tableName;

    @NotBlank(message = "列名不能为空")
    private String columnName;

    @NotNull(message = "向量维度不能为空")
    private Integer vectorDimension;

    private String indexType = "hnsw";

    private Map<String, Object> indexParams;
}
