package com.taskplatform.common.response;

import lombok.Data;
import java.util.List;

@Data
public class PageResult<T> {

    private List<T> items;
    private long total;
    private int page;
    private int pageSize;
    private int totalPages;

    public PageResult() {}

    public PageResult(List<T> items, long total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.totalPages = (int) Math.ceil((double) total / pageSize);
    }

    public static <T> PageResult<T> of(List<T> items, long total, int page, int pageSize) {
        return new PageResult<>(items, total, page, pageSize);
    }
}
