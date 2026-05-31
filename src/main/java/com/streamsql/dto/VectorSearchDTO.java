package com.streamsql.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class VectorSearchDTO {

    @NotNull(message = "查询向量不能为空")
    private List<Float> vector;

    private Integer topK = 10;

    private String filter;
}
