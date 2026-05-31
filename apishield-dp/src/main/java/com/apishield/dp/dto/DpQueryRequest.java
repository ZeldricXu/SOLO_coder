package com.apishield.dp.dto;

import lombok.Data;
import java.util.Map;

@Data
public class DpQueryRequest {
    private String queryId;
    private String userId;
    private String dataSource;
    private String queryType;
    private double originalResult;
    private double sensitivity;
    private double epsilon;
    private double delta;
    private String noiseType;
    private Map<String, Object> queryParams;
}
