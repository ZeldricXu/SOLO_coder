package com.enterprise.engine.common;

import lombok.Data;

@Data
public class PageQuery {

    private Integer pageNum = 1;
    private Integer pageSize = 20;
    private String sortBy;
    private String sortOrder = "desc";
}
