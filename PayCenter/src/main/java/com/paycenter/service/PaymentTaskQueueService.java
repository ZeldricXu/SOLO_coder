package com.paycenter.service;

import com.paycenter.dto.PaymentRequest;
import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.entity.PaymentTask;
import com.paycenter.entity.Transaction;

import java.util.List;

public interface PaymentTaskQueueService {
    boolean isAsyncEnabled(MerchantConfig config);
    void submitPaymentTask(Transaction transaction, PaymentChannel channel, PaymentRequest request);
    PaymentTask getNextPendingTask();
    void markTaskProcessing(String taskId);
    void markTaskSuccess(String taskId, String result);
    void markTaskFailed(String taskId, String errorMessage);
    void markTaskRetry(String taskId, String errorMessage);
    List<PaymentTask> getRetryableTasks();
    void recoverFailedTasks();
    long getPendingTaskCount();
    void processTaskQueue();
}
