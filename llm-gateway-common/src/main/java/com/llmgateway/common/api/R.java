package com.llmgateway.common.api;

import lombok.Data;
import java.io.Serializable;

@Data
public class R<T> implements Serializable {

    private Integer code;
    private String message;
    private T data;
    private Long timestamp;

    private R() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> R<T> success(T data) {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    public static <T> R<T> success() {
        return success(null);
    }

    public static <T> R<T> created(T data) {
        R<T> r = new R<>();
        r.setCode(201);
        r.setMessage("created");
        r.setData(data);
        return r;
    }

    public static <T> R<T> error(Integer code, String message) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    public static <T> R<T> error(String message) {
        return error(500, message);
    }

    public static <T> R<T> validateError(String message) {
        return error(422, message);
    }

    public static <T> R<T> notFound(String message) {
        return error(404, message);
    }
}
