package com.datapipeline.core.error;

import com.datapipeline.common.exception.BusinessException;
import com.datapipeline.common.exception.ValidationError;
import com.datapipeline.core.ProcessResult;
import com.datapipeline.core.metrics.MetricsRecorder;
import com.datapipeline.core.persistence.ResultPersister;
import com.datapipeline.common.tracing.TraceContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ErrorHandler {

    private final MetricsRecorder metricsRecorder;
    private final ResultPersister persister;

    public ErrorHandler(MetricsRecorder metricsRecorder, ResultPersister persister) {
        this.metricsRecorder = metricsRecorder;
        this.persister = persister;
    }

    public ProcessResult handleValidationError(String requestId, ValidationError e, TraceContext traceCtx) {
        log.warn("Validation error: requestId={}, detail={}", requestId, e.getMessage());
        traceCtx.markError("VALIDATION");
        metricsRecorder.recordTraceContext(traceCtx);
        return ProcessResult.error(requestId, "Validation failed", e.getMessage());
    }

    public ProcessResult handleTimeoutError(String requestId, String message, TraceContext traceCtx) {
        log.warn("Timeout error: requestId={}, message={}", requestId, message);
        traceCtx.markError("TIMEOUT");
        metricsRecorder.recordTraceContext(traceCtx);
        persister.persistTimeout(requestId, message);
        return ProcessResult.timeout(requestId, "上游服务响应超时");
    }

    public ProcessResult handleBusinessException(String requestId, BusinessException e, TraceContext traceCtx) {
        log.error("Business error: requestId={}, code={}, message={}", requestId, e.getCode(), e.getMessage());
        if (e.getCode() == 504) {
            return handleTimeoutError(requestId, e.getMessage(), traceCtx);
        }
        traceCtx.markError("BUSINESS_" + e.getCode());
        metricsRecorder.recordTraceContext(traceCtx);
        return ProcessResult.error(requestId, e.getMessage(), e.getErrorDetail());
    }

    public ProcessResult handleUnexpectedError(String requestId, Exception e, TraceContext traceCtx) {
        log.error("Unexpected error during processing: requestId={}", requestId, e);
        traceCtx.markError("INTERNAL");
        metricsRecorder.recordTraceContext(traceCtx);
        return ProcessResult.error(requestId, "内部处理错误", e.getMessage());
    }

    public static void safeRollback(Runnable rollbackAction, String requestId) {
        try {
            rollbackAction.run();
            log.warn("Transaction rolled back for request: {}", requestId);
        } catch (Exception rollbackEx) {
            log.error("Rollback failed for request: {}", requestId, rollbackEx);
        }
    }

}
