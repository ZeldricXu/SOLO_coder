package com.company.dbstudio.core.model;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PaginatedResult<T> {

    private final List<T> data;
    private final int page;
    private final int pageSize;
    private final long totalCount;
    private final long totalPages;

    public PaginatedResult(List<T> data, int page, int pageSize, long totalCount) {
        this.data = data;
        this.page = page;
        this.pageSize = pageSize;
        this.totalCount = totalCount;
        this.totalPages = (long) Math.ceil((double) totalCount / pageSize);
    }

    public List<T> getData() {
        return data;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getTotalPages() {
        return totalPages;
    }

    public boolean hasNextPage() {
        return page < totalPages;
    }

    public boolean hasPreviousPage() {
        return page > 1;
    }

    public boolean isFirstPage() {
        return page == 1;
    }

    public boolean isLastPage() {
        return page >= totalPages;
    }

    public boolean isEmpty() {
        return data == null || data.isEmpty();
    }

    public int size() {
        return data != null ? data.size() : 0;
    }

    public <U> PaginatedResult<U> map(Function<? super T, ? extends U> mapper) {
        List<U> mappedData = data.stream()
                .map(mapper)
                .collect(Collectors.toList());
        return new PaginatedResult<>(mappedData, page, pageSize, totalCount);
    }

    public static <T> PaginatedResult<T> empty(int page, int pageSize) {
        return new PaginatedResult<>(List.of(), page, pageSize, 0);
    }

    public static <T> PaginatedResult<T> of(List<T> data, int page, int pageSize, long totalCount) {
        return new PaginatedResult<>(data, page, pageSize, totalCount);
    }
}
