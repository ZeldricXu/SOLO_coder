package com.tracetopology.web.exception;

import com.tracetopology.common.exception.BusinessException;
import com.tracetopology.common.exception.StorageException;
import com.tracetopology.common.exception.TopologyConsistencyException;
import com.tracetopology.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数非法: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, e.getMessage()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Result<Map<String, String>>> handleValidationException(WebExchangeBindException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("参数校验失败: {}", errors);
        Result<Map<String, String>> result = Result.error(422, "参数校验失败");
        result.setData(errors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(result);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Result<Void>> handleServerWebInputException(ServerWebInputException e) {
        log.warn("请求输入异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, "请求体格式错误: " + e.getReason()));
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Result<Void>> handleNullPointerException(NullPointerException e) {
        log.error("空指针异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统内部错误"));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Result<Map<String, Object>>> handleStorageException(StorageException e) {
        log.warn("存储异常: code={}, operation={}, bucket={}, fileId={}, msg={}",
                e.getCode(), e.getOperation(), e.getBucket(), e.getFileId(), e.getMessage());

        Result<Map<String, Object>> result = Result.error(e.getCode(), e.getMessage());
        result.setData(e.getFullContext());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }

    @ExceptionHandler(TopologyConsistencyException.class)
    public ResponseEntity<Result<Map<String, Object>>> handleTopologyConsistencyException(
            TopologyConsistencyException e) {
        log.error("拓扑一致性异常: code={}, namespace={}, phase={}, msg={}",
                e.getCode(), e.getNamespace(), e.getPhase(), e.getMessage(), e);

        Result<Map<String, Object>> result = Result.error(e.getCode(), e.getMessage());
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", e.getNamespace());
        context.put("phase", e.getPhase());
        context.put("recoverable", e.isRecoverable());
        if (e.getRecoveryInfo() != null) {
            context.putAll(e.getRecoveryInfo());
        }
        result.setData(context);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<Void>> handleRuntimeException(RuntimeException e) {
        log.error("运行时异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统错误: " + e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("未知异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统异常"));
    }
}
