package com.formflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormSubmitResponse {
    private String formId;
    private String instanceId;
    private String status;
    private String currentNodeName;
}
