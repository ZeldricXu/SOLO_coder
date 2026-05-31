package com.streamsql.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.Map;

@Data
public class StreamQueryDTO {

    private String queryName;

    @NotBlank(message = "SQL语句不能为空")
    private String sql;

    private Map<String, Object> executionConfig;
}
