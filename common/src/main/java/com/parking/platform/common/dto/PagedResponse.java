package com.parking.platform.common.dto;

import java.util.List;

public class PagedResponse<T> {

    private List<T> items;
    private Integer page;
    private Integer size;
    private Integer totalPages;
    private Long totalItems;
    private boolean hasNext;
    private boolean hasPrevious;

    public PagedResponse() {}

    public PagedResponse(List<T> items, Integer page, Integer size, Long totalItems) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.totalItems = totalItems;
        this.totalPages = (int) Math.ceil((double) totalItems / size);
        this.hasNext = page < totalPages;
        this.hasPrevious = page > 1;
    }

    public static <T> PagedResponse<T> of(List<T> items, Integer page, Integer size, Long totalItems) {
        return new PagedResponse<>(items, page, size, totalItems);
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }

    public Integer getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(Integer totalPages) {
        this.totalPages = totalPages;
    }

    public Long getTotalItems() {
        return totalItems;
    }

    public void setTotalItems(Long totalItems) {
        this.totalItems = totalItems;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }

    public boolean isHasPrevious() {
        return hasPrevious;
    }

    public void setHasPrevious(boolean hasPrevious) {
        this.hasPrevious = hasPrevious;
    }
}
