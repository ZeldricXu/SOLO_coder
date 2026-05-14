package com.stockmgmt.common;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<T> records;
    private long total;
    private int pageNum;
    private int pageSize;
    private int totalPages;

    public static <T> PageResult<T> of(List<T> records, long total, int pageNum, int pageSize) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setTotalPages(pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0);
        return result;
    }
}
