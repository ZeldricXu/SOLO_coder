package com.formflow.dto;

import com.formflow.entity.ApprovalRecord;
import com.formflow.entity.ProcessInstance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessStatusResponse {
    private ProcessInstance instance;
    private List<ApprovalRecord> history;
    private String formData;
    private String formStatus;
}
