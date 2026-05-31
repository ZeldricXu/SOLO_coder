package com.contractai.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class PageResult<T> {

    private Long total;
    private List<T> list;
    private Integer pageNum;
    private Integer pageSize;
    private Integer pages;

    public PageResult() {
    }

    public PageResult(Long total, List<T> list, Integer pageNum, Integer pageSize) {
        this.total = total;
        this.list = list;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.pages = (int) Math.ceil((double) total / pageSize);
    }
}
