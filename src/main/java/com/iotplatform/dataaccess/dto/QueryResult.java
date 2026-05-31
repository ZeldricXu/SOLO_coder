package com.iotplatform.dataaccess.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryResult {

    private List<Map<String, Object>> rows;

    private long total;

    private long pageNum;

    private long pageSize;

    private long pages;
}
