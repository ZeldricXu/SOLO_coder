package com.cicd.server.dto;

import lombok.Data;
import java.util.Map;

@Data
public class TriggerPipelineRequest {
    private String branch;
    private String triggeredBy;
    private Map<String, String> params;
}
