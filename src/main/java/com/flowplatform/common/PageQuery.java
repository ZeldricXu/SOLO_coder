package com.flowplatform.common;

import lombok.Data;

@Data
public class PageQuery {
    private int pageNum = 1;
    private int pageSize = 10;
    private String keyword;

    public int getOffset() {
        return (pageNum - 1) * pageSize;
    }
}
