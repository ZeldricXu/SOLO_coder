package com.ratelimiter.service.stats;

import java.util.ArrayList;
import java.util.List;

public enum AggregationDimension {
    API_PATH("api_path", "API路径"),
    CLIENT_ID("client_id", "客户端ID"),
    USER_ID("user_id", "用户ID"),
    TIME_PERIOD("time_period", "时间段"),
    HTTP_METHOD("http_method", "HTTP方法"),
    RESPONSE_CODE("response_code", "响应码"),
    IP_ADDRESS("ip_address", "IP地址"),
    REGION("region", "区域");
    
    private final String code;
    private final String description;
    
    AggregationDimension(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static AggregationDimension fromCode(String code) {
        for (AggregationDimension dimension : values()) {
            if (dimension.getCode().equalsIgnoreCase(code)) {
                return dimension;
            }
        }
        return null;
    }
    
    public static List<AggregationDimension> fromCodes(List<String> codes) {
        List<AggregationDimension> dimensions = new ArrayList<>();
        for (String code : codes) {
            AggregationDimension dimension = fromCode(code);
            if (dimension != null) {
                dimensions.add(dimension);
            }
        }
        return dimensions;
    }
}