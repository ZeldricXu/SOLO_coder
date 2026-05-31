package com.smartflow.common.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class ApprovalRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String processId;
    private String businessType;
    private Long businessId;
    private String title;
    private String content;
    private Map<String, Object> variables;
    private Long initiatorId;
}
