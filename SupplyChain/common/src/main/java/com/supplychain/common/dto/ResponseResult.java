package com.supplychain.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseResult<T> implements Serializable {
    private Integer code;
    private String message;
    private T data;

    public static <T> ResponseResult<T> success() {
        return ResponseResult.<T>builder()
                .code(200)
                .message("success")
                .build();
    }

    public static <T> ResponseResult<T> success(T data) {
        return ResponseResult.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .build();
    }

    public static <T> ResponseResult<T> success(String message, T data) {
        return ResponseResult.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ResponseResult<T> error(String message) {
        return ResponseResult.<T>builder()
                .code(500)
                .message(message)
                .build();
    }

    public static <T> ResponseResult<T> error(Integer code, String message) {
        return ResponseResult.<T>builder()
                .code(code)
                .message(message)
                .build();
    }

    public static <T> ResponseResult<T> error(String message, T data) {
        return ResponseResult.<T>builder()
                .code(500)
                .message(message)
                .data(data)
                .build();
    }
}
