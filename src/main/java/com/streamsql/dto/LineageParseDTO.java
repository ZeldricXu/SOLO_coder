package com.streamsql.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class LineageParseDTO {

    private String sourceType = "SQL";

    @NotBlank(message = "SQL语句不能为空")
    private String sql;
}
