package com.web3platform.crosschainbridge.exception;

import com.web3platform.crosschainbridge.model.BridgeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class BridgeExceptionHandler {

    @ExceptionHandler(BridgeException.class)
    public ResponseEntity<Map<String, Object>> handleBridgeException(BridgeException ex) {
        log.warn("Bridge exception: code={}, message={}", ex.getErrorCode(), ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        if (ex.getSourceChain() != null) {
            body.put("sourceChain", ex.getSourceChain());
        }
        if (ex.getTargetChain() != null) {
            body.put("targetChain", ex.getTargetChain());
        }

        HttpStatus status = determineHttpStatus(ex.getErrorCode());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", BridgeErrorCode.INVALID_REQUEST);
        body.put("message", ex.getMessage());

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected bridge error", ex);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", "BRIDGE_999");
        body.put("message", "Internal bridge service error");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private HttpStatus determineHttpStatus(String errorCode) {
        return switch (errorCode) {
            case BridgeErrorCode.LOCK_NOT_FOUND, BridgeErrorCode.MESSAGE_NOT_FOUND,
                 BridgeErrorCode.INVALID_REQUEST, BridgeErrorCode.SIGNATURE_INVALID,
                 BridgeErrorCode.PROOF_VERIFICATION_FAILED -> HttpStatus.BAD_REQUEST;
            case BridgeErrorCode.INVALID_LOCK_STATUS, BridgeErrorCode.MINT_ALREADY_EXISTS,
                 BridgeErrorCode.ATOMICITY_VIOLATION, BridgeErrorCode.AMOUNT_MISMATCH -> HttpStatus.CONFLICT;
            case BridgeErrorCode.RPC_CONNECTION_FAILED, BridgeErrorCode.POOL_EXHAUSTED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
