package com.taskflow.common.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    private long timestamp;
    private String traceId;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        result.setTimestamp(Instant.now().toEpochMilli());
        return result;
    }

    public static <T> Result<T> success(String message, T data) {
        Result<T> result = success(data);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setTimestamp(Instant.now().toEpochMilli());
        return result;
    }

    public static <T> Result<T> error(int code, String message, String traceId) {
        Result<T> result = error(code, message);
        result.setTraceId(traceId);
        return result;
    }

    public boolean isSuccess() {
        return code == 200 || code == 201;
    }
}
