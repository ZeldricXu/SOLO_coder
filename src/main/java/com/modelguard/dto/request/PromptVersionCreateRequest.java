package com.modelguard.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class PromptVersionCreateRequest {

    private String promptId;

    private String content;

    private Map<String, Object> variables;

    private String description;

    private String createdBy;
}
