package com.medical.appointment.dto;

import java.util.HashMap;
import java.util.Map;

public class ApiResponse<T> {
    private Integer code;
    private String message;
    private T data;
    private Map<String, Object> additionalInfo;

    public ApiResponse() {
        this.additionalInfo = new HashMap<>();
    }

    public ApiResponse(Integer code, String message) {
        this.code = code;
        this.message = message;
        this.additionalInfo = new HashMap<>();
    }

    public ApiResponse(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.additionalInfo = new HashMap<>();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "操作成功", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(400, message, null);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(404, message, null);
    }

    public static <T> ApiResponse<T> serverError(String message) {
        return new ApiResponse<>(500, message, null);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Map<String, Object> getAdditionalInfo() {
        return additionalInfo;
    }

    public void setAdditionalInfo(Map<String, Object> additionalInfo) {
        this.additionalInfo = additionalInfo;
    }

    public void addInfo(String key, Object value) {
        this.additionalInfo.put(key, value);
    }
}
