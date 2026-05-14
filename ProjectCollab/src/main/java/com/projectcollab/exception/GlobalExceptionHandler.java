package com.projectcollab.exception;

import com.projectcollab.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProjectCollabException.class)
    public ResponseEntity<ApiResponse<Void>> handleProjectCollabException(ProjectCollabException ex) {
        return ResponseEntity
                .status(ex.getCode())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        return ResponseEntity
                .status(500)
                .body(ApiResponse.error(500, ex.getMessage() != null ? ex.getMessage() : "Internal Server Error"));
    }
}
