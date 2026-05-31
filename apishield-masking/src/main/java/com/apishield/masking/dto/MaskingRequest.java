package com.apishield.masking.dto;

import com.apishield.domain.vo.UserContext;
import lombok.Data;
import java.util.Map;

@Data
public class MaskingRequest {
    private String dataSource;
    private String tableName;
    private Map<String, Object> data;
    private UserContext userContext;
}
