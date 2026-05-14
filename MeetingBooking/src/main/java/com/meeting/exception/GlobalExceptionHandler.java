package com.meeting.exception;

import com.meeting.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MeetingException.class)
    public ResponseEntity<ApiResponse<Object>> handleMeetingException(MeetingException ex) {
        return ResponseEntity.status(ex.getCode())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneralException(Exception ex) {
        return ResponseEntity.status(500)
                .body(ApiResponse.error(500, "服务器内部错误: " + ex.getMessage()));
    }
}
