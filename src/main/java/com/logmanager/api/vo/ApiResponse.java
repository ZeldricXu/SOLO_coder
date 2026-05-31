package com.logmanager.api.vo;

import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;
    private Instant timestamp;
    private Map<String, Object> pagination;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(200);
        response.setMessage("success");
        response.setData(data);
        response.setTimestamp(Instant.now());
        return response;
    }

    public static <T> ApiResponse<T> success(T data, Map<String, Object> pagination) {
        ApiResponse<T> response = success(data);
        response.setPagination(pagination);
        return response;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        response.setTimestamp(Instant.now());
        return response;
    }

    public static <T> ApiResponse<T> created(T data) {
        ApiResponse<T> response = success(data);
        response.setCode(201);
        return response;
    }
}
