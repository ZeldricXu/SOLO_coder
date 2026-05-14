package com.social.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SocialNetworkException.class)
    public ResponseEntity<Map<String, Object>> handleSocialNetworkException(SocialNetworkException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", ex.getErrorCode());
        response.put("message", ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode() >= 500 ? 500 : ex.getErrorCode()).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 500);
        response.put("message", "系统内部错误: " + ex.getMessage());
        return ResponseEntity.status(500).body(response);
    }
}
