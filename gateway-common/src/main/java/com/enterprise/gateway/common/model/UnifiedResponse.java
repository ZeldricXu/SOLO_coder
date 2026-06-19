package com.enterprise.gateway.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedResponse<T> {

    private Integer code;

    private String message;

    private T data;

    private Long timestamp;

    public static <T> UnifiedResponse<T> success(T data) {
        return UnifiedResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> UnifiedResponse<T> error(Integer code, String message) {
        return UnifiedResponse.<T>builder()
                .code(code)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
