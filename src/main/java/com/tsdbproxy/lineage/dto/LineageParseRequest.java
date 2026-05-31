package com.tsdbproxy.lineage.dto;

import lombok.Data;

@Data
public class LineageParseRequest {

    private String sql;
    private String targetTable;
}
