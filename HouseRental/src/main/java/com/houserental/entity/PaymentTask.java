package com.houserental.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTask implements Serializable {

    private String taskId;
    private String paymentId;
    private String contractId;
    private String tenantId;
    private String landlordId;
    private double amount;
    private String paymentMethod;
    private String paymentPeriod;
    private int retryCount = 0;
    private LocalDateTime submittedAt;
    private LocalDateTime lastRetryAt;
    private String status;
    private String errorMessage;

    public static PaymentTask create(String paymentId, String contractId, String tenantId,
                                      String landlordId, double amount, String paymentMethod,
                                      String paymentPeriod) {
        PaymentTask task = new PaymentTask();
        task.setTaskId("PT_" + paymentId);
        task.setPaymentId(paymentId);
        task.setContractId(contractId);
        task.setTenantId(tenantId);
        task.setLandlordId(landlordId);
        task.setAmount(amount);
        task.setPaymentMethod(paymentMethod);
        task.setPaymentPeriod(paymentPeriod);
        task.setSubmittedAt(LocalDateTime.now());
        task.setStatus("PENDING");
        return task;
    }
}
