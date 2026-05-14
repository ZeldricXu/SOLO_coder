package com.authcenter.exception;

public class GlobalExceptionHandler {
    
    @org.springframework.web.bind.annotation.ExceptionHandler(AuthException.class)
    public com.authcenter.dto.ApiResponse<?> handleAuthException(AuthException e) {
        return com.authcenter.dto.ApiResponse.error(e.getCode(), e.getMessage());
    }
    
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public com.authcenter.dto.ApiResponse<?> handleValidationException(org.springframework.web.bind.MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("参数验证失败");
        return com.authcenter.dto.ApiResponse.error(400, message);
    }
    
    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    public com.authcenter.dto.ApiResponse<?> handleException(Exception e) {
        return com.authcenter.dto.ApiResponse.error(500, "服务器内部错误: " + e.getMessage());
    }
}