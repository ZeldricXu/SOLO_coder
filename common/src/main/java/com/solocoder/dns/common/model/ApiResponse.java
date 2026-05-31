package com.solocoder.dns.common.model;

import lombok.Data;
import java.io.Serializable;

@Data
public class ApiResponse<T> implements Serializable {
    private int code;
    private String message;
    private T data;
    private Long timestamp;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(200);
        resp.setMessage("success");
        resp.setData(data);
        resp.setTimestamp(System.currentTimeMillis());
        return resp;
    }

    public static <T> ApiResponse<T> success(int code, T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(code);
        resp.setMessage("success");
        resp.setData(data);
        resp.setTimestamp(System.currentTimeMillis());
        return resp;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(code);
        resp.setMessage(message);
        resp.setTimestamp(System.currentTimeMillis());
        return resp;
    }

    public static <T> ApiResponse<T> error(int code, String message, T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(code);
        resp.setMessage(message);
        resp.setData(data);
        resp.setTimestamp(System.currentTimeMillis());
        return resp;
    }
}
