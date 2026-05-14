package com.datasync.dto;

import com.datasync.common.Constants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    @JsonProperty("code")
    private int code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("data")
    private T data;

    public ApiResponse() {
        this.code = Constants.API_CODE_SUCCESS;
    }

    public ApiResponse(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
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

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(Constants.API_CODE_SUCCESS, "Success", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(Constants.API_CODE_SUCCESS, message, data);
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(Constants.API_CODE_BAD_REQUEST, message);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(Constants.API_CODE_NOT_FOUND, message);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(Constants.API_CODE_INTERNAL_ERROR, message);
    }

    public static <T> ApiResponse<T> conflict(String message) {
        return new ApiResponse<>(Constants.API_CODE_CONFLICT, message);
    }
}
