package com.dynamiclog.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class PageResult<T> {
    private List<T> items;
    private long total;
    private int page;
    private int pageSize;
    private int totalPages;
}
