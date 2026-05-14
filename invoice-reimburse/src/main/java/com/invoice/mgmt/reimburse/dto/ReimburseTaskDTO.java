package com.invoice.mgmt.reimburse.dto;

import com.invoice.mgmt.common.dto.InvoiceReimburseRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReimburseTaskDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private InvoiceReimburseRequest request;
    private int priority;
    private long submitTime;

    public static ReimburseTaskDTO fromTask(com.invoice.mgmt.reimburse.service.AsyncInvoiceReimburseService.ReimburseTask task) {
        return ReimburseTaskDTO.builder()
                .taskId(task.getTaskId())
                .request(task.getRequest())
                .priority(task.getPriority())
                .submitTime(task.getSubmitTime())
                .build();
    }
}
