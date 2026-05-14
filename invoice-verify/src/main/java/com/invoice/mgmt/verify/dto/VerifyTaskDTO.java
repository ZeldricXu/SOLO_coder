package com.invoice.mgmt.verify.dto;

import com.invoice.mgmt.common.dto.InvoiceVerifyRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyTaskDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private InvoiceVerifyRequest request;
    private long submitTime;
    private int retryCount;

    public static VerifyTaskDTO fromTask(com.invoice.mgmt.verify.service.AsyncInvoiceVerifyService.VerifyTask task) {
        return VerifyTaskDTO.builder()
                .taskId(task.getTaskId())
                .request(task.getRequest())
                .submitTime(task.getSubmitTime())
                .retryCount(task.getRetryCount())
                .build();
    }
}
