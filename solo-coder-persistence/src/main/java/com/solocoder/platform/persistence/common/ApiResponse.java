package com.solocoder.platform.persistence.common;

import lombok.Data;

import java.io.Serializable;

@Data
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private int code;
    private String message;
    private T data;
    private Pagination pagination;

    public ApiResponse() {
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> success(T data, Pagination pagination) {
        ApiResponse<T> response = new ApiResponse<>(200, "success", data);
        response.setPagination(pagination);
        return response;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> conflict(String resourceId) {
        return new ApiResponse<>(409, "并发冲突，资源ID: " + resourceId, null);
    }

    @Data
    public static class Pagination {
        private int page;
        private int size;
        private long total;
        private int totalPages;

        public Pagination() {
        }

        public Pagination(int page, int size, long total) {
            this.page = page;
            this.size = size;
            this.total = total;
            this.totalPages = (int) Math.ceil((double) total / size);
        }
    }
}
